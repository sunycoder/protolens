package com.lashouwan.protolens.enums;

/** 本机采集进程生命周期；EXTERNAL 表示不属于当前后端，不接管。 */
public enum CollectorState {
    NOT_STARTED,
    DISABLED,
    PREPARING,
    EXTERNAL,
    MISSING,
    RUNNING,
    FAILED,
    STOPPED
}
