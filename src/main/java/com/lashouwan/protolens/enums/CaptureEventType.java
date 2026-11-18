package com.lashouwan.protolens.enums;

/** 本机采集事件的传输类型；外部编码保留小写，未知类型在推进顺序游标前拒绝。 */
public enum CaptureEventType {
    OPEN("open"),
    FRAME("frame"),
    PACKET("packet"),
    CLOSE("close"),
    HEARTBEAT("heartbeat"),
    ERROR("error");
    private final String code;

    CaptureEventType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CaptureEventType fromCode(String value) {
        for (CaptureEventType type : values()) {
            if (type.code.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知采集事件类型：" + value);
    }
}
