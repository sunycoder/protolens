package com.lashouwan.protolens.desktop.proxy;

import java.util.Locale;

/** 平台代理快照；Windows 保留 WinINet 默认 LAN 字段，macOS 保存单个网络服务的完整检查快照。 */
public record ProxySettings(
        int flags, String server, String bypass, String autoConfigUrl, MacProxySettings mac) {
    public ProxySettings(int flags, String server, String bypass, String autoConfigUrl) {
        this(flags, server, bypass, autoConfigUrl, null);
    }

    public static final int DIRECT = 1;
    public static final int MANUAL = 2;
    public static final int PAC = 4;
    public static final int AUTO_DETECT = 8;

    public ProxySettings {
        server = server == null ? "" : server;
        bypass = bypass == null ? "" : bypass;
        autoConfigUrl = autoConfigUrl == null ? "" : autoConfigUrl;
    }

    public boolean hasProxyConfiguration() {
        if (mac != null) {
            return mac.hasProxyConfiguration();
        }
        return (flags & (MANUAL | PAC | AUTO_DETECT)) != 0;
    }

    /** 仅显示配置类型及脱敏后的代理地址，不把 PAC URL 中的私密参数传到页面。 */
    public String description() {
        if (mac != null) {
            return mac.service() + "：" + (mac.hasProxyConfiguration() ? "已配置代理" : "未启用代理");
        }
        if ((flags & MANUAL) != 0) {
            return "手动代理：" + server.replaceAll("[^;=\\s/]+@", "***@");
        }
        if ((flags & PAC) != 0) {
            return "已启用自动代理脚本（PAC）";
        }
        if ((flags & AUTO_DETECT) != 0) {
            return "已启用自动检测代理";
        }
        return "未启用系统代理";
    }

    /** 识别旧版手动遗留的同端口采集代理；此时不存在可恢复的更早 VPN 配置。 */
    public boolean pointsTo(int port) {
        if (mac != null) {
            return mac.pointsTo(port);
        }
        String value =
                server.toLowerCase(Locale.ROOT).replace(" ", "").replace("localhost", "127.0.0.1");
        String address = "127.0.0.1:" + port;
        return (flags & MANUAL) != 0
                && (value.equals(address)
                        || value.equals("http=" + address + ";https=" + address)
                        || value.equals("https=" + address + ";http=" + address));
    }

    public static ProxySettings local(int port, ProxySettings original) {
        String bypass = original.bypass();
        if (!bypass.isBlank()) {
            bypass += ";";
        }
        bypass += "<local>;localhost;127.*;[::1]";
        return new ProxySettings(
                DIRECT | MANUAL,
                "http=127.0.0.1:" + port + ";https=127.0.0.1:" + port,
                bypass,
                original.autoConfigUrl());
    }
}
