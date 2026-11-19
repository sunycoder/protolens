#import <Foundation/Foundation.h>
#import <Security/Authorization.h>
#import <SystemConfiguration/SystemConfiguration.h>
#include <stdio.h>
#include <string.h>

// 以当前用户运行，通过 Authorization Services 取得网络设置权限；不启动 root shell、不接受命令。
// 授权引用只留在本进程内存中。stdin 关闭即退出，系统可在任何时刻撤销或要求重新授权。
static AuthorizationRef authorization = NULL;
static BOOL authorized = NO;
static NSArray<NSString *> *keys;
static NSDictionary<NSString *, NSString *> *getters;

static void fail(NSString *message) {
    @throw [NSException exceptionWithName:@"ProxySession" reason:message userInfo:nil];
}

static NSString *field(NSString *value, NSString *name) {
    for (NSString *line in [value componentsSeparatedByString:@"\n"]) {
        NSString *prefix = [name stringByAppendingString:@":"];
        if ([line hasPrefix:prefix]) {
            return [[line substringFromIndex:prefix.length]
                    stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        }
    }
    fail(@"无法识别代理快照字段");
    return nil;
}

static BOOL enabled(NSString *value, NSString *name) {
    NSString *text = field(value, name);
    if ([@[@"Yes", @"On"] containsObject:text]) return YES;
    if ([@[@"No", @"Off"] containsObject:text]) return NO;
    fail(@"无法识别代理开关");
    return NO;
}

// 先验证整份请求；不接收文件路径、可执行文件名、shell 或修改授权数据库的操作。
static NSDictionary *validate(NSDictionary *request) {
    if (![request isKindOfClass:NSDictionary.class] || request.count != 2) fail(@"请求格式无效");
    NSDictionary *expected = request[@"expected"], *target = request[@"target"];
    for (NSDictionary *snapshot in @[expected ?: @{}, target ?: @{}]) {
        if (![snapshot isKindOfClass:NSDictionary.class] || snapshot.count != 3) fail(@"快照格式无效");
        for (NSString *name in @[@"service", @"device"]) {
            id value = snapshot[name];
            if (![value isKindOfClass:NSString.class] || ![value length] || [value length] > 1024
                    || [value rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) {
                fail(@"网络服务信息无效");
            }
        }
        NSDictionary *values = snapshot[@"values"];
        if (![values isKindOfClass:NSDictionary.class] || values.count != keys.count) fail(@"代理类别不完整");
        for (NSString *key in keys) {
            if (![values[key] isKindOfClass:NSString.class] || [values[key] length] > 16384) fail(@"代理值无效");
        }
        for (NSString *key in @[@"http", @"https"]) {
            NSString *value = values[key];
            if (![field(value, @"Authenticated Proxy Enabled") isEqual:@"0"]) fail(@"不支持认证代理");
            NSString *port = field(value, @"Port");
            if (!port.length || [port rangeOfCharacterFromSet:NSCharacterSet.decimalDigitCharacterSet.invertedSet].location != NSNotFound
                    || port.length > 5 || port.integerValue > 65535) fail(@"代理端口无效");
            field(value, @"Server");
            enabled(value, @"Enabled");
        }
        enabled(values[@"pac"], @"Enabled");
        enabled(values[@"discovery"], @"Auto Proxy Discovery");
    }
    if (![expected[@"service"] isEqual:target[@"service"]] || ![expected[@"device"] isEqual:target[@"device"]]) {
        fail(@"不能跨网络服务修改代理");
    }
    NSDictionary *before = expected[@"values"], *after = target[@"values"];
    if (![before[@"socks"] isEqual:after[@"socks"]] || ![before[@"bypass"] isEqual:after[@"bypass"]]
            || ![field(before[@"pac"], @"URL") isEqual:field(after[@"pac"], @"URL")]) {
        fail(@"不允许修改 SOCKS、绕过项或 PAC 地址");
    }
    return target;
}

static NSString *readSetting(NSString *service, NSString *getter) {
    NSTask *task = [[NSTask alloc] init];
    task.executableURL = [NSURL fileURLWithPath:@"/usr/sbin/networksetup"];
    task.arguments = @[getter, service];
    NSMutableDictionary *environment = [NSProcessInfo.processInfo.environment mutableCopy];
    environment[@"LC_ALL"] = @"C";
    environment[@"LANG"] = @"C";
    task.environment = environment;
    NSPipe *pipe = [NSPipe pipe];
    task.standardOutput = pipe;
    task.standardError = [NSFileHandle fileHandleWithNullDevice];
    NSError *error = nil;
    if (![task launchAndReturnError:&error]) fail(@"无法读取当前网络设置");
    NSData *output = [pipe.fileHandleForReading readDataToEndOfFile];
    [task waitUntilExit];
    if (task.terminationStatus != 0 || output.length > 131072) fail(@"读取当前网络设置失败");
    NSString *text = [[NSString alloc] initWithData:output encoding:NSUTF8StringEncoding];
    if (!text) fail(@"网络设置输出编码无效");
    return [text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

static void checkSnapshot(NSDictionary *expected) {
    for (NSString *key in keys) {
        if (![readSetting(expected[@"service"], getters[key]) isEqual:expected[@"values"][key]]) {
            fail(@"授权期间系统代理已变化，未覆盖当前设置，请重新确认");
        }
    }
}

static void authorize(void) {
    if (authorized) return;
    if (!authorization && AuthorizationCreate(NULL, kAuthorizationEmptyEnvironment, kAuthorizationFlagDefaults, &authorization) != errAuthorizationSuccess) {
        fail(@"无法创建 macOS 网络设置授权会话");
    }
    AuthorizationItem item = {"system.preferences.network", 0, NULL, 0};
    AuthorizationRights rights = {1, &item};
    const char *prompt = "ProtoLens 需要授权修改和恢复当前网络的 HTTP/HTTPS 代理。授权仅在本次运行中复用。";
    AuthorizationItem environmentItem = {kAuthorizationEnvironmentPrompt, strlen(prompt), (void *)prompt, 0};
    AuthorizationEnvironment environment = {1, &environmentItem};
    OSStatus status = AuthorizationCopyRights(authorization, &rights, &environment,
        kAuthorizationFlagInteractionAllowed | kAuthorizationFlagExtendRights | kAuthorizationFlagPreAuthorize, NULL);
    if (status == errAuthorizationCanceled) fail(@"已取消 macOS 授权，未修改代理");
    if (status != errAuthorizationSuccess) fail(@"macOS 网络设置授权失败");
    authorized = YES;
}

static void writeProxy(NSDictionary *request) {
    NSDictionary *target = validate(request);
    authorize();
    SCPreferencesRef preferences = SCPreferencesCreateWithAuthorization(NULL, CFSTR("ProtoLens Proxy Session"), NULL, authorization);
    if (!preferences) fail(@"无法打开网络设置");
    BOOL locked = NO;
    @try {
        if (!SCPreferencesLock(preferences, false)) {
            if (SCError() == kSCStatusAccessError) {
                authorized = NO;
                fail(@"网络授权已失效，请重新点击操作完成授权");
            }
            fail(@"网络设置正在被其他程序修改，请稍后重试");
        }
        locked = YES;
        SCPreferencesSynchronize(preferences);
        checkSnapshot(request[@"expected"]);
        CFArrayRef servicesRef = SCNetworkServiceCopyAll(preferences);
        if (!servicesRef) fail(@"无法读取网络服务");
        NSArray *services = CFBridgingRelease(servicesRef);
        SCNetworkServiceRef selected = NULL;
        NSUInteger matches = 0;
        for (id serviceObject in services) {
            SCNetworkServiceRef service = (__bridge SCNetworkServiceRef)serviceObject;
            SCNetworkInterfaceRef interface = SCNetworkServiceGetInterface(service);
            if (!interface) continue;
            if (SCNetworkServiceGetEnabled(service)
                    && [(__bridge NSString *)SCNetworkServiceGetName(service) isEqual:target[@"service"]]
                    && [(__bridge NSString *)SCNetworkInterfaceGetBSDName(interface) isEqual:target[@"device"]]) {
                selected = service;
                matches++;
            }
        }
        if (matches != 1) fail(@"网络服务已变化或不能唯一定位，未修改代理");
        SCNetworkProtocolRef protocol = SCNetworkServiceCopyProtocol(selected, kSCNetworkProtocolTypeProxies);
        if (!protocol) fail(@"当前网络服务不支持代理设置");
        @try {
            NSDictionary *original = (__bridge NSDictionary *)SCNetworkProtocolGetConfiguration(protocol);
            NSMutableDictionary *configuration = original ? [original mutableCopy] : [NSMutableDictionary dictionary];
            NSDictionary *values = target[@"values"];
            for (NSString *key in @[@"http", @"https"]) {
                NSString *prefix = [key isEqual:@"http"] ? @"HTTP" : @"HTTPS";
                configuration[[prefix stringByAppendingString:@"Enable"]] = @(enabled(values[key], @"Enabled"));
                configuration[[prefix stringByAppendingString:@"Proxy"]] = field(values[key], @"Server");
                configuration[[prefix stringByAppendingString:@"Port"]] = @(field(values[key], @"Port").integerValue);
            }
            configuration[@"ProxyAutoConfigEnable"] = @(enabled(values[@"pac"], @"Enabled"));
            configuration[@"ProxyAutoDiscoveryEnable"] = @(enabled(values[@"discovery"], @"Auto Proxy Discovery"));
            if (!SCNetworkProtocolSetConfiguration(protocol, (__bridge CFDictionaryRef)configuration)
                    || !SCPreferencesCommitChanges(preferences)) {
                authorized = NO;
                fail(@"系统拒绝写入代理；授权可能已失效，请重试，恢复备份已保留");
            }
            // commit 成功但 apply 失败时保留已提交值和上层恢复租约，不能伪报成功或覆盖第三方状态。
            if (!SCPreferencesApplyChanges(preferences)) fail(@"代理已保存但应用失败，请刷新状态并恢复；备份已保留");
        } @finally {
            CFRelease(protocol);
        }
    } @finally {
        if (locked) SCPreferencesUnlock(preferences);
        CFRelease(preferences);
    }
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        keys = @[@"http", @"https", @"pac", @"discovery", @"socks", @"bypass"];
        getters = @{@"http":@"-getwebproxy", @"https":@"-getsecurewebproxy", @"pac":@"-getautoproxyurl",
            @"discovery":@"-getproxyautodiscovery", @"socks":@"-getsocksfirewallproxy", @"bypass":@"-getproxybypassdomains"};
        BOOL validateOnly = argc == 2 && strcmp(argv[1], "--validate-only") == 0;
        if (argc != 1 && !validateOnly) return 2;
        char buffer[131074];
        while (fgets(buffer, sizeof(buffer), stdin)) {
            @autoreleasepool {
                NSDictionary *response;
                @try {
                    size_t length = strlen(buffer);
                    if (length > 131072 || !length || buffer[length - 1] != '\n') fail(@"代理请求超过大小限制或未完整结束");
                    NSData *data = [NSData dataWithBytes:buffer length:length];
                    NSError *error = nil;
                    id request = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
                    if (error) fail(@"代理请求不是有效 JSON");
                    if (validateOnly) validate(request); else writeProxy(request);
                    response = @{@"ok":@YES};
                } @catch (NSException *error) {
                    response = @{@"ok":@NO, @"message":error.reason ?: @"macOS 代理操作失败"};
                }
                NSData *output = [NSJSONSerialization dataWithJSONObject:response options:0 error:NULL];
                fwrite(output.bytes, 1, output.length, stdout);
                fputc('\n', stdout);
                fflush(stdout);
            }
        }
        if (authorization) AuthorizationFree(authorization, kAuthorizationFlagDestroyRights);
    }
    return 0;
}
