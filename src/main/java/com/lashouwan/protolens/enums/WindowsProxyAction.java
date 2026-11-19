package com.lashouwan.protolens.enums;

/** Windows 随包脚本支持的固定动作；code 与 PowerShell 参数保持一致。 */
public enum WindowsProxyAction {
    READ("Read"),
    WRITE("Write"),
    COMPARE_WRITE("CompareWrite");

    private final String code;

    WindowsProxyAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
