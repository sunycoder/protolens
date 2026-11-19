package com.lashouwan.protolens.enums;

/** 页面启动方式；AUTO 仅用于配置默认值，启动解析后必须确定为 DEV 或 STATIC。 */
public enum FrontendMode {
    AUTO("auto"),
    DEV("dev"),
    STATIC("static");

    private final String code;

    FrontendMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static FrontendMode fromCode(String code) {
        for (FrontendMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("frontendMode 必须为 auto、dev 或 static");
    }
}
