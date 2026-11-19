package com.lashouwan.protolens.enums;

/** 字典结果的定义种类；结构体没有协议 ID 或收发方向。 */
public enum ProtocolDefinitionKind {
    MESSAGE("message"),
    STRUCTURE("structure");
    private final String code;

    ProtocolDefinitionKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
