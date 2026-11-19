package com.lashouwan.protolens.constant;

/** 本机服务的地址约束和协议端口范围；具体监听端口仍由 AnalyzerConfig 提供。 */
public final class NetworkConstants {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final String LOCALHOST = "localhost";
    public static final String HTTP_LOOPBACK_PREFIX = "http://" + LOOPBACK_HOST + ":";
    public static final int MAX_PORT = 65535;

    private NetworkConstants() {}
}
