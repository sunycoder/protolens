package com.lashouwan.protolens.desktop.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** macOS 原生 networksetup 适配；只操作已确认网络服务，通过系统授权框执行固定命令。 */
public final class MacSystemProxyBackend implements SystemProxyBackend {
    static final List<String> KEYS =
            List.of("http", "https", "pac", "discovery", "socks", "bypass");
    private static final Map<String, String> GETTERS =
            Map.of(
                    "http",
                    "-getwebproxy",
                    "https",
                    "-getsecurewebproxy",
                    "pac",
                    "-getautoproxyurl",
                    "discovery",
                    "-getproxyautodiscovery",
                    "socks",
                    "-getsocksfirewallproxy",
                    "bypass",
                    "-getproxybypassdomains");
    private final CommandRunner runner;

    @FunctionalInterface
    interface CommandRunner {
        String run(List<String> command, boolean authorized) throws Exception;
    }

    public MacSystemProxyBackend() {
        this(MacSystemProxyBackend::execute);
    }

    MacSystemProxyBackend(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean supported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    @Override
    public boolean requiresAuthorization() {
        return true;
    }

    @Override
    public ProxySettings read() throws Exception {
        // scutil 的 PrimaryInterface 同时适用于 IPv4 和 IPv6；VPN/TUN 不匹配物理服务时停止猜测。
        String device = primaryDevice();
        String service =
                serviceForDevice(run("/usr/sbin/networksetup", "-listnetworkserviceorder"), device);
        return readService(service, device).snapshot();
    }

    private String primaryDevice() throws Exception {
        String output = run("/usr/sbin/scutil", "--nwi");
        var match = Pattern.compile("(?m)^Network interfaces: (\\S+)").matcher(output);
        if (!match.find()) {
            throw new IOException("未找到当前联网接口，请连网后刷新代理状态，或手动设置代理");
        }
        return match.group(1);
    }

    static String serviceForDevice(String output, String device) throws IOException {
        String service = null;
        var found = new ArrayList<String>();
        for (String line : output.split("\n")) {
            var name = Pattern.compile("^\\((\\d+|\\*)\\) (.+)$").matcher(line.strip());
            if (name.matches()) {
                service = name.group(1).equals("*") ? null : name.group(2);
            } else if (line.contains("Device: " + device + ")") && service != null) {
                found.add(service);
                service = null;
            }
        }
        if (found.size() != 1 || found.getFirst().startsWith("*")) {
            throw new IOException("当前接口无法唯一匹配已启用的网络服务，请手动设置代理");
        }
        return found.getFirst();
    }

    private MacProxySettings readService(String service, String device) throws Exception {
        var values = new LinkedHashMap<String, String>();
        for (String key : KEYS) {
            values.put(key, run("/usr/sbin/networksetup", GETTERS.get(key), service).strip());
        }
        var result = new MacProxySettings(service, device, values);
        result.hasProxyConfiguration(); // 输出格式未知时拒绝执行写操作，不当成无代理。
        return result;
    }

    @Override
    public ProxySettings readFor(ProxySettings snapshot) throws Exception {
        return snapshot == null || snapshot.mac() == null
                ? read()
                : readService(snapshot.mac().service(), snapshot.mac().device()).snapshot();
    }

    @Override
    public String notice(ProxySettings snapshot) throws Exception {
        if (snapshot != null && snapshot.mac() != null) {
            try {
                if (!primaryDevice().equals(snapshot.mac().device())) {
                    return "网络接口已切换，当前仅接管 " + snapshot.mac().service() + "；请先恢复原代理，再接管新网络";
                }
            } catch (IOException unavailable) {
                return "当前未联网，仍可尝试恢复 " + snapshot.mac().service() + " 的原代理";
            }
        }
        return "";
    }

    @Override
    public ProxySettings local(int port, ProxySettings original) {
        return original.mac().local(port).snapshot();
    }

    @Override
    public ProxySettings recoveryOriginal(int port, ProxySettings current) {
        return current.pointsTo(port) ? current.mac().disableLocal().snapshot() : current;
    }

    @Override
    public void write(ProxySettings settings) throws Exception {
        compareAndWrite(readFor(settings), settings);
    }

    @Override
    public void compareAndWrite(ProxySettings expected, ProxySettings settings) throws Exception {
        if (!readFor(expected).equals(expected)) {
            throw new IOException("系统代理已变化，请重新确认");
        }
        String shell = transaction(expected.mac(), settings.mac());
        // shell 参数与 AppleScript 字符串分别编码；网络服务名、PAC 地址和中文均不能成为代码。
        String script =
                "do shell script "
                        + appleString(shell)
                        + " with administrator privileges with prompt \"ProtoLens 需要修改或恢复当前网络的"
                        + " HTTP/HTTPS 代理\"";
        runner.run(List.of("/usr/bin/osascript", "-e", script), true);
    }

    /** 授权结束后再次核对全部快照；失败时倒序回滚已完成项，外部改过的项不覆盖。 */
    static String transaction(MacProxySettings expected, MacProxySettings target) {
        if (!expected.service().equals(target.service())
                || !expected.device().equals(target.device())) {
            throw new IllegalArgumentException("不能跨网络服务写入代理快照");
        }
        StringBuilder shell = new StringBuilder("export LC_ALL=C; export LANG=C;\n");
        for (String key : KEYS) {
            shell.append("[ \"$(")
                    .append(getter(expected, key))
                    .append(")\" = ")
                    .append(quote(expected.values().get(key)))
                    .append(" ] || exit 3\n");
        }
        List<String> changed =
                KEYS.stream()
                        .filter(key -> !expected.values().get(key).equals(target.values().get(key)))
                        .toList();
        shell.append("rollback() { trap - EXIT HUP INT TERM;\n");
        for (int i = changed.size() - 1; i >= 0; i--) {
            String key = changed.get(i);
            shell.append("if [ \"${done_")
                    .append(key)
                    .append(":-0}\" = 1 ] && [ \"$(")
                    .append(getter(expected, key))
                    .append(")\" = \"$after_")
                    .append(key)
                    .append("\" ]; then\n")
                    .append(setter(expected, key))
                    .append("; fi\n");
        }
        shell.append("}; trap 'rollback' EXIT; trap 'exit 4' HUP INT TERM\n");
        for (String key : changed) {
            // 每个已完成的类别记录实际输出，失败后仅撤销仍保持这个值的类别。
            shell.append("step_ok=0; ")
                    .append(setter(target, key))
                    .append(" && step_ok=1\n")
                    .append("after_")
                    .append(key)
                    .append("=$(")
                    .append(getter(expected, key))
                    .append(") || exit 4\n")
                    .append("done_")
                    .append(key)
                    .append("=1\n")
                    .append("[ \"$step_ok\" = 1 ] || exit 4\n");
        }
        for (String key : KEYS) {
            shell.append("[ \"$(")
                    .append(getter(target, key))
                    .append(")\" = ")
                    .append(quote(target.values().get(key)))
                    .append(" ] || exit 4\n");
        }
        return shell.append("trap - EXIT HUP INT TERM\n").toString();
    }

    private static String getter(MacProxySettings settings, String key) {
        return "/usr/sbin/networksetup " + GETTERS.get(key) + " " + quote(settings.service());
    }

    private static String setter(MacProxySettings settings, String key) {
        String prefix = "/usr/sbin/networksetup ";
        String service = quote(settings.service());
        String enabled = settings.enabled(key) ? "on" : "off";
        if (key.equals("http") || key.equals("https")) {
            String value = settings.values().get(key);
            if (!MacProxySettings.field(value, "Authenticated Proxy Enabled").equals("0")) {
                throw new IllegalArgumentException("无法安全恢复含密码的认证代理");
            }
            int port = Integer.parseInt(MacProxySettings.field(value, "Port"));
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("macOS 代理端口无效");
            }
            String command = key.equals("http") ? "webproxy" : "securewebproxy";
            return prefix
                    + "-set"
                    + command
                    + " "
                    + service
                    + " "
                    + quote(MacProxySettings.field(value, "Server"))
                    + " "
                    + port
                    + " off && "
                    + prefix
                    + "-set"
                    + command
                    + "state "
                    + service
                    + " "
                    + enabled;
        }
        if (key.equals("pac")) {
            return prefix + "-setautoproxystate " + service + " " + enabled;
        }
        if (key.equals("discovery")) {
            return prefix + "-setproxyautodiscovery " + service + " " + enabled;
        }
        throw new IllegalArgumentException("不允许改写 SOCKS 或代理绕过项");
    }

    static String quote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    static String appleString(String text) {
        return "\""
                + text.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                + "\"";
    }

    private String run(String... command) throws Exception {
        return runner.run(List.of(command), false);
    }

    private static String execute(List<String> command, boolean authorized) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        Process process = builder.start();
        // 持续读取避免长网络服务名或多服务输出填满管道；不把原配置/PAC URL写到日志。
        var output =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return process.getInputStream().readNBytes(131072);
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        });
        try {
            process.getOutputStream().close();
            if (!process.waitFor(authorized ? 120 : 10, TimeUnit.SECONDS)) {
                throw new IOException("macOS 代理操作超时，请检查系统授权框并刷新代理状态");
            }
            String text =
                    new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                if (text.contains("-128")) {
                    throw new IOException("已取消 macOS 系统授权，代理操作未完成");
                }
                throw new IOException("macOS 代理操作未完成，请检查网络或授权状态后重试；原配置备份用于恢复");
            }
            return text;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
