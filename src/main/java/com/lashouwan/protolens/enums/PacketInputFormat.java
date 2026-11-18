package com.lashouwan.protolens.enums;

/** 采集输入格式，已解压消息不能冒充原始 WebSocket 帧。 */
public enum PacketInputFormat {
    DECOMPRESSED_MESSAGE,
    WEBSOCKET_WIRE_FRAME
}
