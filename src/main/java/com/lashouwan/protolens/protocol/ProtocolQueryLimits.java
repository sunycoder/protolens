package com.lashouwan.protolens.protocol;

/** 协议目录的展示和查询边界；搜索总数不因单页及引用展示上限而截断。 */
public final class ProtocolQueryLimits {
    public static final int PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_QUERY_TERMS = 8;
    public static final int MAX_TYPE_DEPTH = 32;
    public static final int FIELD_PAGE_SIZE = 100;
    public static final int MAX_REFERENCES = 200;
    public static final int MAX_MATCH_PATH_LENGTH = 4096;
    public static final int MAX_CACHED_CAPTURE_DICTIONARIES = 4;
    public static final int SOURCE_CONTEXT_BEFORE = 8;
    public static final int SOURCE_CONTEXT_LINES = 60;
    public static final int MAX_SOURCE_BYTES = 64 * 1024 * 1024;

    private ProtocolQueryLimits() {}
}
