package com.lashouwan.protolens.enums;

/** 连接身份的证据状态；登录请求只能进入待确认，当前玩家协议才可提供已识别身份。 */
public enum ConnectionIdentityState {
    PENDING("pending"),
    IDENTIFIED("identified");

    private final String code;

    ConnectionIdentityState(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
