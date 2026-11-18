package com.lashouwan.protolens.capture;

import com.lashouwan.protolens.protocol.WebSocketStreamDecoder;

/** 单条握手的采集运行态；由 LiveCaptureService 的锁保护，CS/SC 解压上下文独立且不跨连接复用。 */
final class LiveConnection implements AutoCloseable {
    /** 本机缓存/历史记录 ID，不是游戏角色 ID。 */
    String captureId;

    /** 已移除查询参数的连接地址。 */
    final String endpoint;

    /** 原握手协商扩展，用于清空后新记录保留真实来源元数据。 */
    final String extensions;

    /** 客户端发送方向的解压上下文。 */
    final WebSocketStreamDecoder client;

    /** 服务端下发方向的解压上下文。 */
    final WebSocketStreamDecoder server;

    /** 已接收帧数，包含尚未重组为完整消息的分片。 */
    long frames;

    /** 已经写入缓存的累计消息数，不随淘汰减少。 */
    long messages;

    /** 解码失败的累计消息数。 */
    long failed;

    /** 连接上下文失效原因，非空时不再复用 inflater。 */
    String error = "";

    LiveConnection(String captureId, String endpoint, String extensions) {
        this.captureId = captureId;
        this.endpoint = endpoint;
        this.extensions = extensions;
        this.client = new WebSocketStreamDecoder(extensions.contains("client_no_context_takeover"));
        this.server = new WebSocketStreamDecoder(extensions.contains("server_no_context_takeover"));
    }

    @Override
    public void close() {
        client.close();
        server.close();
    }
}
