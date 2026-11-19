package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.DecodeStatus;
import com.lashouwan.protolens.enums.MessageDirection;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;

/** 解析竞品大端消息头；兼容真实抓包的服务端扩展序号头，消息体使用 Thrift Compact。 */
public final class GamePacketDecoder {
    /** 普通头为 10 字节；SC 扩展标志增加两个序号字节，含义仍按原始证据保留。 */
    private static final int BASE_HEADER_BYTES = 10;

    private static final int EXTENDED_HEADER_BYTES = 12;
    private static final int EXTENDED_SEQUENCE_FLAG = 2;
    private final ProtocolCatalog catalog;
    private final ObjectMapper mapper;

    public GamePacketDecoder(ProtocolCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** 解码一个完整业务包；失败也保留字节、协议号和可定位原因，供界面检查。 */
    public ObjectNode decode(byte[] packet, String direction) {
        ObjectNode result = mapper.createObjectNode();
        result.put("direction", direction);
        result.put("packetHex", HexFormat.of().formatHex(packet));
        result.put("bytes", packet.length);
        try {
            if (packet.length < BASE_HEADER_BYTES
                    || packet.length > AnalyzerLimits.MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("业务包长度无效：" + packet.length);
            }
            ByteBuffer header = ByteBuffer.wrap(packet);
            result.put("flag", Short.toUnsignedInt(header.getShort()));
            result.put("sequence", Short.toUnsignedInt(header.getShort()));
            // 当前抓包的 SC flag 含位 2 时，多出两字节序号；语义尚未由客户端代码确认。
            // 只保存原值，不据此宣称请求响应关联。CS 仍按客户端代码中的十字节头解析。
            int headerBytes = BASE_HEADER_BYTES;
            if (MessageDirection.SC.name().equals(direction)
                    && (result.path("flag").asInt() & EXTENDED_SEQUENCE_FLAG) != 0) {
                if (packet.length < EXTENDED_HEADER_BYTES) {
                    throw new IllegalArgumentException("服务端扩展头截断");
                }
                result.put("extraSequence", Short.toUnsignedInt(header.getShort()));
                headerBytes = EXTENDED_HEADER_BYTES;
            }
            result.put("headerBytes", headerBytes);
            int id = Short.toUnsignedInt(header.getShort());
            long length = Integer.toUnsignedLong(header.getInt());
            result.put("protocolId", id);
            result.put("protocolName", catalog.name(id));
            result.put("bodyBytes", length);
            if (length != packet.length - headerBytes) {
                throw new IllegalArgumentException(
                        "消息头长度 " + length + " 与实际消息体 " + (packet.length - headerBytes) + " 不一致");
            }
            CompactThriftDecoder decoder =
                    new CompactThriftDecoder(
                            catalog,
                            mapper,
                            Arrays.copyOfRange(packet, headerBytes, packet.length));
            result.set("body", decoder.decode(id));
            result.set("warnings", decoder.warnings());
            String expected = catalog.message(id).path("direction").asText();
            if (!expected.isBlank() && !"UNKNOWN".equals(expected) && !expected.equals(direction)) {
                result.withArray("warnings").add("抓包方向与字典消息前缀不一致");
            }
            result.put(
                    "status",
                    !catalog.mapped(id)
                            ? DecodeStatus.UNMAPPED.name()
                            : result.withArray("warnings").isEmpty()
                                    ? DecodeStatus.DECODED.name()
                                    : DecodeStatus.PARTIAL.name());
        } catch (RuntimeException failure) {
            result.remove("body");
            result.put("status", DecodeStatus.FAILED.name());
            result.put("error", failure.getMessage() == null ? "解码失败" : failure.getMessage());
        }
        return result;
    }
}
