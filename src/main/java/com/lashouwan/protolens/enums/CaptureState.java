package com.lashouwan.protolens.enums;

/** 单条采集记录的生命周期；与是否保存到磁盘分别表示。 */
public enum CaptureState {
    CAPTURING,
    STOPPED,
    CLOSED,
    INTERRUPTED
}
