package com.lashouwan.protolens.enums;

/** 字典中的 Thrift TType 编号；与 Compact 在线字节编号不同，不得互换。 */
public enum ThriftType {
    BOOL(2),
    BYTE(3),
    DOUBLE(4),
    I16(6),
    I32(8),
    I64(10),
    STRING(11),
    STRUCT(12),
    MAP(13),
    SET(14),
    LIST(15);

    private final int code;

    ThriftType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
