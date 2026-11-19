package com.lashouwan.protolens.mcp;

/** AI 查询的传输与扫描预算，避免整份抓包或客户端源码一次进入模型上下文。 */
public final class McpLimits {
    /** 独立 MCP 凭证的长度边界，不与采集器令牌共用。 */
    public static final int MIN_TOKEN_LENGTH = 32;

    public static final int MAX_TOKEN_LENGTH = 200;
    public static final int REQUEST_BYTES = 64 * 1024;
    public static final int RESULT_BYTES = 512 * 1024;
    public static final int PAGE_SIZE = 100;
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SOURCE_FILES = 100000;
    public static final int MAX_SOURCE_MATCHES = 100;
    public static final int MAX_SOURCE_LINES = 200;
    public static final int MAX_LINE_CHARS = 1200;
    public static final long SCAN_MILLIS = 5000;

    private McpLimits() {}
}
