package com.lashouwan.protolens.enums;

/** 支持的系统身份；code 与配置、页面和发行包命名保持一致，不能使用枚举名称替换外部值。 */
public enum OperatingSystem {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux");

    private final String code;

    OperatingSystem(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 严格解析已规范化的平台代码，未知值交由入口报告，不回退成另一种系统。 */
    public static OperatingSystem fromCode(String code) {
        for (OperatingSystem system : values()) {
            if (system.code.equals(code)) {
                return system;
            }
        }
        throw new IllegalArgumentException("不支持的操作系统: " + code);
    }
}
