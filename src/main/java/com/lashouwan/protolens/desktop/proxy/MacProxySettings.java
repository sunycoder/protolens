package com.lashouwan.protolens.desktop.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单个 macOS 网络服务的原始输出快照；PAC URL 和绕过项仅用于一致性检查，不在接管时改写。 */
public record MacProxySettings(String service, String device, Map<String, String> values) {
    public MacProxySettings {
        if (service == null
                || service.isBlank()
                || device == null
                || device.isBlank()
                || values == null) {
            throw new IllegalArgumentException("macOS 代理快照缺少网络服务");
        }
        values = Map.copyOf(values);
        for (String key : MacSystemProxyBackend.KEYS) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("macOS 代理快照字段不完整");
            }
        }
    }

    static String field(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).strip();
            }
        }
        throw new IllegalArgumentException("无法识别 macOS 代理字段：" + key);
    }

    boolean enabled(String key) {
        String name = key.equals("discovery") ? "Auto Proxy Discovery" : "Enabled";
        String value = field(values.get(key), name);
        if (!value.equals("Yes")
                && !value.equals("No")
                && !value.equals("On")
                && !value.equals("Off")) {
            throw new IllegalArgumentException("无法识别 macOS 代理开关");
        }
        return value.equals("Yes") || value.equals("On");
    }

    boolean hasProxyConfiguration() {
        return enabled("http")
                || enabled("https")
                || enabled("pac")
                || enabled("discovery")
                || enabled("socks");
    }

    boolean pointsTo(int port) {
        for (String key : new String[] {"http", "https"}) {
            String value = values.get(key);
            if (!enabled(key)
                    || !field(value, "Server").equals("127.0.0.1")
                    || !field(value, "Port").equals(Integer.toString(port))) {
                return false;
            }
        }
        return !enabled("pac") && !enabled("discovery") && !enabled("socks");
    }

    /** 接管只改 HTTP/HTTPS 和自动代理开关，保留其他字段的原始顺序及内容，供完整快照比较。 */
    MacProxySettings local(int port) {
        var result = new LinkedHashMap<>(values);
        for (String key : new String[] {"http", "https"}) {
            String value = values.get(key);
            if (!field(value, "Authenticated Proxy Enabled").equals("0")) {
                throw new IllegalArgumentException("当前网络含认证代理，无法备份原密码，请保留原设置并手动配置采集代理");
            }
            value = replace(value, "Enabled", "Yes");
            value = replace(value, "Server", "127.0.0.1");
            result.put(key, replace(value, "Port", Integer.toString(port)));
        }
        result.put("pac", replace(values.get("pac"), "Enabled", "No"));
        result.put("discovery", replace(values.get("discovery"), "Auto Proxy Discovery", "Off"));
        if (enabled("socks")) {
            throw new IllegalArgumentException("当前网络启用了 SOCKS 代理，请先在原代理软件中关闭后再接管，避免流量绕过采集器");
        }
        return new MacProxySettings(service, device, result);
    }

    MacProxySettings disableLocal() {
        var result = new LinkedHashMap<>(values);
        for (String key : new String[] {"http", "https"}) {
            result.put(key, replace(values.get(key), "Enabled", "No"));
        }
        return new MacProxySettings(service, device, result);
    }

    private static String replace(String value, String key, String replacement) {
        field(value, key);
        return value.replaceAll(
                "(?m)^" + java.util.regex.Pattern.quote(key) + ":.*$",
                java.util.regex.Matcher.quoteReplacement(key + ": " + replacement));
    }

    ProxySettings snapshot() {
        return new ProxySettings(0, "", "", "", this);
    }
}
