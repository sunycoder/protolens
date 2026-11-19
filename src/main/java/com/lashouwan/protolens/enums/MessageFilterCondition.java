package com.lashouwan.protolens.enums;

/** 可单独解除的消息筛选；位掩码用于无结果诊断，不改变过滤顺序或查询合同。 */
public enum MessageFilterCondition {
    RANGE("range", "匹配消息不在当前历史区间", "取消区间限制"),
    DIRECTION("direction", "其他条件下有消息被方向筛选隐藏", "取消方向筛选"),
    STATUS("status", "其他条件下有消息被状态筛选隐藏", "取消状态筛选"),
    CONNECTION("connection", "匹配消息属于其他连接", "取消连接筛选"),
    PROTOCOL_ID("protocolId", "其他条件下有消息被协议 ID 限制隐藏", "取消协议 ID 限制"),
    MESSAGE_INDEX("messageIndex", "其他条件下有消息被消息序号限制隐藏", "取消序号限制"),
    HEARTBEAT("hideHeartbeat", "匹配消息被隐藏心跳设置隐藏", "显示心跳"),
    INCLUDE("include", "匹配消息不在协议包含条件中", "取消包含限制"),
    EXCLUDE("exclude", "匹配协议已被排除", "取消对应排除项");

    private final String field;
    private final String description;
    private final String action;

    MessageFilterCondition(String field, String description, String action) {
        this.field = field;
        this.description = description;
        this.action = action;
    }

    public int mask() {
        return 1 << ordinal();
    }

    public String field() {
        return field;
    }

    public String description() {
        return description;
    }

    public String action() {
        return action;
    }
}
