package com.lashouwan.protolens.enums;

/** Compact 在线类型与字典 TType 的映射；两个布尔编码共享 BOOL，STOP 由结构体读取入口单独处理。 */
public enum CompactType {
    BOOLEAN_TRUE(1, ThriftType.BOOL),
    BOOLEAN_FALSE(2, ThriftType.BOOL),
    BYTE(3, ThriftType.BYTE),
    I16(4, ThriftType.I16),
    I32(5, ThriftType.I32),
    I64(6, ThriftType.I64),
    DOUBLE(7, ThriftType.DOUBLE),
    BINARY(8, ThriftType.STRING),
    LIST(9, ThriftType.LIST),
    SET(10, ThriftType.SET),
    MAP(11, ThriftType.MAP),
    STRUCT(12, ThriftType.STRUCT);

    private final int code;
    private final ThriftType schemaType;

    /** 枚举初始化时建立固定索引，解码热路径不遍历枚举、不分配 values 数组。 */
    private static final CompactType[] BY_CODE = new CompactType[13];

    static {
        for (CompactType type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    CompactType(int code, ThriftType schemaType) {
        this.code = code;
        this.schemaType = schemaType;
    }

    public int code() {
        return code;
    }

    public ThriftType schemaType() {
        return schemaType;
    }

    public static CompactType fromCode(int code) {
        if (code <= 0 || code >= BY_CODE.length) {
            throw new IllegalArgumentException("非法 Compact 类型 " + code);
        }
        return BY_CODE[code];
    }
}
