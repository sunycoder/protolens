package com.lashouwan.protolens.enums;

/** 消息按稳定编号排序；分页与跟随使用同一种顺序定义。 */
public enum MessageOrder {
    ASC("asc"),
    DESC("desc");
    private final String code;

    MessageOrder(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MessageOrder fromCode(String code) {
        for (MessageOrder order : values()) {
            if (order.code.equals(code)) {
                return order;
            }
        }
        throw new IllegalArgumentException("排序必须为 asc 或 desc");
    }
}
