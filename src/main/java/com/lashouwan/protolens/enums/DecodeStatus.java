package com.lashouwan.protolens.enums;

/** 解码结构与字典匹配状态；序列化名称保持现有抓包格式。 */
public enum DecodeStatus {
    DECODED,
    PARTIAL,
    UNMAPPED,
    FAILED
}
