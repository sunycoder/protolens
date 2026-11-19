package com.lashouwan.protolens.enums;

import com.lashouwan.protolens.constant.ProxyConstants;

import java.util.List;

/** macOS 代理类别及原生命令；code 保持历史租约 JSON 的键名，绕过项和 SOCKS 仅用于检查。 */
public enum MacProxyKind {
    HTTP("http", "-getwebproxy", "-setwebproxy", "-setwebproxystate"),
    HTTPS("https", "-getsecurewebproxy", "-setsecurewebproxy", "-setsecurewebproxystate"),
    PAC("pac", "-getautoproxyurl", null, "-setautoproxystate"),
    DISCOVERY("discovery", "-getproxyautodiscovery", null, "-setproxyautodiscovery"),
    SOCKS("socks", "-getsocksfirewallproxy", null, null),
    BYPASS("bypass", "-getproxybypassdomains", null, null);

    /** HTTP/HTTPS 同时接管；此列表的顺序也是写入与倒序恢复的顺序。 */
    public static final List<MacProxyKind> WEB_PROXIES = List.of(HTTP, HTTPS);

    public static final List<MacProxyKind> SWITCHABLE = List.of(HTTP, HTTPS, PAC, DISCOVERY, SOCKS);

    private final String code;
    private final String readCommand;
    private final String addressCommand;
    private final String stateCommand;

    MacProxyKind(String code, String readCommand, String addressCommand, String stateCommand) {
        this.code = code;
        this.readCommand = readCommand;
        this.addressCommand = addressCommand;
        this.stateCommand = stateCommand;
    }

    public String code() {
        return code;
    }

    public String readCommand() {
        return readCommand;
    }

    public String addressCommand() {
        return addressCommand;
    }

    public String stateCommand() {
        return stateCommand;
    }

    public String enabledField() {
        return this == DISCOVERY ? "Auto Proxy Discovery" : ProxyConstants.FIELD_ENABLED;
    }
}
