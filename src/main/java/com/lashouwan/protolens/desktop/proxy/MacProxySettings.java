package com.lashouwan.protolens.desktop.proxy;

import com.lashouwan.protolens.constant.NetworkConstants;
import com.lashouwan.protolens.constant.ProxyConstants;
import com.lashouwan.protolens.enums.MacProxyKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        for (MacProxyKind kind : MacProxyKind.values()) {
            if (!values.containsKey(kind.code())) {
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

    /** 快照继续用原有字符串键落盘，业务代码只能通过枚举读取，防止类别拼写错误。 */
    String value(MacProxyKind kind) {
        return values.get(kind.code());
    }

    boolean enabled(MacProxyKind kind) {
        return switch (field(value(kind), kind.enabledField())) {
            case "Yes", "On" -> true;
            case "No", "Off" -> false;
            default -> throw new IllegalArgumentException("无法识别 macOS 代理开关");
        };
    }

    boolean hasProxyConfiguration() {
        for (MacProxyKind kind : MacProxyKind.SWITCHABLE) {
            if (enabled(kind)) {
                return true;
            }
        }
        return false;
    }

    boolean pointsTo(int port) {
        for (MacProxyKind kind : MacProxyKind.WEB_PROXIES) {
            String value = value(kind);
            if (!enabled(kind)
                    || !NetworkConstants.LOOPBACK_HOST.equals(
                            field(value, ProxyConstants.FIELD_SERVER))
                    || !field(value, ProxyConstants.FIELD_PORT).equals(Integer.toString(port))) {
                return false;
            }
        }
        return !enabled(MacProxyKind.PAC)
                && !enabled(MacProxyKind.DISCOVERY)
                && !enabled(MacProxyKind.SOCKS);
    }

    /** 接管只改 HTTP/HTTPS 和自动代理开关，保留其他字段的原始顺序及内容，供完整快照比较。 */
    MacProxySettings local(int port) {
        var result = new LinkedHashMap<>(values);
        for (MacProxyKind kind : MacProxyKind.WEB_PROXIES) {
            String value = value(kind);
            if (!ProxyConstants.AUTHENTICATION_DISABLED.equals(
                    field(value, ProxyConstants.FIELD_AUTHENTICATED))) {
                throw new IllegalArgumentException("当前网络含认证代理，无法备份原密码，请保留原设置并手动配置采集代理");
            }
            value = replace(value, kind.enabledField(), "Yes");
            value = replace(value, ProxyConstants.FIELD_SERVER, NetworkConstants.LOOPBACK_HOST);
            result.put(
                    kind.code(), replace(value, ProxyConstants.FIELD_PORT, Integer.toString(port)));
        }
        result.put(
                MacProxyKind.PAC.code(),
                replace(value(MacProxyKind.PAC), MacProxyKind.PAC.enabledField(), "No"));
        result.put(
                MacProxyKind.DISCOVERY.code(),
                replace(
                        value(MacProxyKind.DISCOVERY),
                        MacProxyKind.DISCOVERY.enabledField(),
                        "Off"));
        if (enabled(MacProxyKind.SOCKS)) {
            throw new IllegalArgumentException("当前网络启用了 SOCKS 代理，请先在原代理软件中关闭后再接管，避免流量绕过采集器");
        }
        return new MacProxySettings(service, device, result);
    }

    MacProxySettings disableLocal() {
        var result = new LinkedHashMap<>(values);
        for (MacProxyKind kind : MacProxyKind.WEB_PROXIES) {
            result.put(kind.code(), replace(value(kind), kind.enabledField(), "No"));
        }
        return new MacProxySettings(service, device, result);
    }

    private static String replace(String value, String key, String replacement) {
        field(value, key);
        return value.replaceAll(
                "(?m)^" + Pattern.quote(key) + ":.*$",
                Matcher.quoteReplacement(key + ": " + replacement));
    }

    ProxySettings snapshot() {
        return new ProxySettings(0, "", "", "", this);
    }
}
