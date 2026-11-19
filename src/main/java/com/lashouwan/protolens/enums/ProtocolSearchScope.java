package com.lashouwan.protolens.enums;

/** 协议目录搜索范围；字段和结构体范围包含通过结构引用抵达的协议。 */
public enum ProtocolSearchScope {
    ALL("all"),
    NAME("name"),
    FIELD("field"),
    STRUCTURE("structure");

    private final String code;

    ProtocolSearchScope(String code) {
        this.code = code;
    }

    public static ProtocolSearchScope fromCode(String code) {
        for (ProtocolSearchScope scope : values()) {
            if (scope.code.equals(code)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("查询范围必须为 all、name、field 或 structure");
    }
}
