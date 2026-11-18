package com.lashouwan.protolens.constant;

/** 本机分析工具的资源边界；损坏包不能导致无界解压、递归或分配。 */
public final class AnalyzerLimits {
    /** 字节预算统一按 MiB 换算，先扩宽再乘。 */
    public static final long BYTES_PER_MIB = 1024L * 1024;

    /** 实时连接和未保存记录的数量边界，避免多连接绕过单连接预算。 */
    public static final int MAX_LIVE_RECORDS = 32;

    /** 单批事件和最近重试去重窗口大小；两者不代表游戏消息序号。 */
    public static final int MAX_BATCH_EVENTS = 128;

    public static final int RETRY_WINDOW_EVENTS = 8192;

    /** SSE 页面连接数量及保活间隔，单位毫秒。 */
    public static final int MAX_SSE_SUBSCRIBERS = 20;

    public static final long SSE_HEARTBEAT_MILLIS = 15_000;

    /** 消息列表默认页大小和单次查询上限。 */
    public static final int DEFAULT_PAGE_SIZE = 100;

    public static final int MAX_PAGE_SIZE = 500;

    /** 单个上传文件及单个解压归档成员最多 128 MiB。 */
    public static final int MAX_ARCHIVE_BYTES = 128 * 1024 * 1024;

    /** 单条解压后的业务消息最多 16 MiB。 */
    public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    /** 单次导入最多保留五万条业务消息，超过时整个导入失败。 */
    public static final int MAX_MESSAGES = 50_000;

    /** 所有归档成员累计解压最多 256 MiB。 */
    public static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;

    private AnalyzerLimits() {}
}
