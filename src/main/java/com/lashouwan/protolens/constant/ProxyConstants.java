package com.lashouwan.protolens.constant;

/** 平台代理命令、输出字段与执行预算；不承载租约和当前配置等运行状态。 */
public final class ProxyConstants {
    public static final String NETWORK_SETUP = "/usr/sbin/networksetup";
    public static final String SCUTIL = "/usr/sbin/scutil";
    public static final String OSASCRIPT = "/usr/bin/osascript";
    public static final String FIELD_ENABLED = "Enabled";
    public static final String FIELD_SERVER = "Server";
    public static final String FIELD_PORT = "Port";
    public static final String FIELD_AUTHENTICATED = "Authenticated Proxy Enabled";
    public static final String AUTHENTICATION_DISABLED = "0";
    public static final int MAX_COMMAND_OUTPUT_BYTES = 128 * 1024;
    public static final int READ_TIMEOUT_SECONDS = 10;
    public static final int AUTHORIZATION_TIMEOUT_SECONDS = 120;
    public static final int OUTPUT_TIMEOUT_SECONDS = 5;
    public static final int WINDOWS_TIMEOUT_SECONDS = 15;
    public static final int MAX_WINDOWS_OUTPUT_BYTES = 64 * 1024;
    public static final String APPLE_USER_CANCELLED_CODE = "-128";

    private ProxyConstants() {}
}
