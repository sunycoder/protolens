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

    /** 连接身份展示及历史保留上限，不限制消息原始内容。 */
    public static final int MAX_CONNECTION_NOTE_LENGTH = 40;

    public static final int MAX_IDENTITY_NICKNAME_LENGTH = 100;
    public static final int MAX_IDENTITY_HISTORY = 256;

    /** 记录元数据与采集事件分别校验，不能把同值上限当成同一业务字段。 */
    public static final int MAX_CAPTURE_NAME_LENGTH = 100;

    public static final int MAX_OPERATION_CONTEXT_LENGTH = 2000;
    public static final int MAX_LIVE_ENDPOINT_LENGTH = 2048;
    public static final int MAX_WEBSOCKET_EXTENSIONS_LENGTH = 1024;
    public static final int MAX_EVENT_TIME_LENGTH = 100;
    public static final int MAX_CAPTURE_ERROR_LENGTH = 2048;
    public static final int MAX_PROTOCOL_OVERVIEW_GROUPS = 200;

    /** 缓存预算的统一校验区间，默认值由启动配置及仓库共同使用。 */
    public static final int DEFAULT_CACHE_MESSAGES = 10_000;

    public static final int MIN_CACHE_MESSAGES = 100;
    public static final int MAX_CACHE_MESSAGES = 50_000;
    public static final int DEFAULT_CACHE_MIB = 64;
    public static final int MIN_CACHE_MIB = 1;
    public static final int MAX_CACHE_MIB = 256;

    /** Compact 解码的递归、容器和总读取预算，互相独立。 */
    public static final int MAX_THRIFT_DEPTH = 64;

    public static final int MAX_THRIFT_CONTAINER_ELEMENTS = 100_000;
    public static final int MAX_THRIFT_READ_OPERATIONS = 1_000_000;

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
