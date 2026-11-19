package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.protocol.CompactThriftDecoder;
import com.lashouwan.protolens.protocol.GamePacketDecoder;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.WebSocketStreamDecoder;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.Deflater;

/** 聚焦协议解析边界，不调用游戏服务器，不验证或重放捐献业务。 */
class PacketDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 固定字节样本独立于枚举，防止 Compact 编号和字典 TType 编号在重构后串用。 */
    @Test
    void testAllCompactTypes_ShouldDecodeFixedWireSampleWithoutWarnings() throws Exception {
        ProtocolCatalog catalog =
                new ProtocolCatalog(
                        mapper.readTree(
                                """
{"schemaVersion":1,"messages":{"100":{"name":"GcTypes"}},"definitions":{
  "100":{"1":["yes",2],"2":["no",2],"3":["byte",3],"4":["short",6],
    "5":["int",8],"6":["long",10],"7":["double",4],"8":["string",11],
    "9":["list",[15,2]],"10":["set",[14,8]],"11":["map",[13,8,11]],
    "12":["nested",[12,"Nested"]]},"Nested":{"1":["child",8]}}}
"""));
        // 字段依次为 true、false、-2、-2、4、6、1.5、ok、布尔列表、整数集合、Map 和嵌套结构。
        byte[] bytes =
                HexFormat.of()
                        .parseHex(
                                "111213fe14031508160c17000000000000f83f18026f6b192101021a150e1b01580201781c15120000");
        var decoder = new CompactThriftDecoder(catalog, mapper, bytes);
        var body = decoder.decode(100);
        assertTrue(body.path("yes").asBoolean());
        assertFalse(body.path("no").asBoolean());
        assertEquals(-2, body.path("byte").asInt());
        assertEquals(-2, body.path("short").asInt());
        assertEquals(4, body.path("int").asInt());
        assertEquals("6", body.path("long").asText());
        assertEquals(1.5, body.path("double").asDouble());
        assertEquals("ok", body.path("string").asText());
        assertTrue(body.path("list").path(0).asBoolean());
        assertFalse(body.path("list").path(1).asBoolean());
        assertEquals(7, body.path("set").path(0).asInt());
        assertEquals(1, body.path("map").path("$map").path(0).path("key").asInt());
        assertEquals("x", body.path("map").path("$map").path(0).path("value").asText());
        assertEquals(9, body.path("nested").path("child").asInt());
        assertTrue(decoder.warnings().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CompactThriftDecoder(catalog, mapper, HexFormat.of().parseHex("1d00"))
                                .decode(100));
    }

    /** [11,1] 来自新版客户端 StringKind.BINARY，不可因内容合法 UTF-8 就改为文本。 */
    @Test
    void testExplicitBinaryString_ShouldPreserveBytesWithoutFalseWarning() throws Exception {
        ProtocolCatalog catalog =
                new ProtocolCatalog(
                        mapper.readTree(
                                """
                                {"schemaVersion":1,"messages":{"100":{"name":"GcBinary"}},
                                "definitions":{"100":{"1":["payload",[11,1]],"2":["text",11]}}}
                                """));
        var decoder =
                new CompactThriftDecoder(
                        catalog, mapper, HexFormat.of().parseHex("180361626318026f6b00"));
        var decoded = decoder.decode(100);
        assertEquals("YWJj", decoded.path("payload").path("$base64").asText());
        assertEquals("ok", decoded.path("text").asText());
        assertTrue(decoder.warnings().isEmpty());
        var invalidText =
                new CompactThriftDecoder(catalog, mapper, HexFormat.of().parseHex("2801ff00"));
        assertEquals("/w==", invalidText.decode(100).path("text").path("$base64").asText());
        assertEquals(1, invalidText.warnings().size());
    }

    private ProtocolCatalog catalog() throws Exception {
        return new ProtocolCatalog(
                mapper.readTree(
                        """
{"schemaVersion":1,"messages":{"2364":{"name":"CgAllianceTechDonate","direction":"CS"},
"12":{"name":"GcLogin","direction":"SC"},"99":{"name":"GcTest","direction":"SC"}},
"definitions":{"2364":{"1":["techGroupId",8],"2":["isUseCp",2],"3":["level",8]},
"12":{"1":["ok",2]},"99":{"1":["large",10],"2":["items",[15,8]],"3":["mapping",[13,8,11]]}}}
"""));
    }

    /** 截图中的实际压缩字节应还原为 2364 及三个字段，保护解压与解码层之间的契约。 */
    @Test
    void testCapturedDonation_ShouldDecodeActualFields() throws Exception {
        byte[] payload = HexFormat.of().parseHex("62606208e4b46160606017fdc42f24cac10000");
        byte[] frame =
                ByteBuffer.allocate(payload.length + 2)
                        .put((byte) 0xc2)
                        .put((byte) payload.length)
                        .put(payload)
                        .array();
        try (WebSocketStreamDecoder stream = new WebSocketStreamDecoder(true)) {
            var decoded =
                    new GamePacketDecoder(catalog(), mapper).decode(stream.accept(frame), "CS");
            assertEquals("DECODED", decoded.path("status").asText());
            assertEquals(2364, decoded.path("protocolId").asInt());
            assertEquals(1017, decoded.path("body").path("techGroupId").asInt());
            assertFalse(decoded.path("body").path("isUseCp").asBoolean());
            assertEquals(4, decoded.path("body").path("level").asInt());
        }
    }

    /** 实际服务端扩展头多出序号，不能把它误当消息编号；长度不匹配必须显式失败。 */
    @Test
    void testServerExtendedHeader_ShouldPreserveSequenceAndRejectTruncation() throws Exception {
        var decoder = new GamePacketDecoder(catalog(), mapper);
        byte[] packet = HexFormat.of().parseHex("000300070009000c000000021100");
        var result = decoder.decode(packet, "SC");
        assertEquals("DECODED", result.path("status").asText());
        assertEquals(12, result.path("protocolId").asInt());
        assertEquals(9, result.path("extraSequence").asInt());
        assertEquals(7, result.path("sequence").asInt());
        assertTrue(result.path("body").path("ok").asBoolean());
        var truncated = decoder.decode(Arrays.copyOf(packet, packet.length - 1), "SC");
        assertEquals("FAILED", truncated.path("status").asText());
        assertFalse(truncated.has("body"));
    }

    /** 超过 JS 安全整数的 I64 不得变为 double；列表、Map 的键和值按真实类型读取。 */
    @Test
    void testCompactContainers_ShouldPreserveInt64AndMapKeys() throws Exception {
        byte[] body = HexFormat.of().parseHex("16feffffffffffffff7f192501041b015801017800");
        var decoded = new CompactThriftDecoder(catalog(), mapper, body).decode(99);
        assertEquals("4611686018427387903", decoded.path("large").asText());
        assertEquals(-1, decoded.path("items").path(0).asInt());
        assertEquals(2, decoded.path("items").path(1).asInt());
        assertEquals(-1, decoded.path("mapping").path("$map").path(0).path("key").asInt());
        assertEquals("x", decoded.path("mapping").path("$map").path(0).path("value").asText());
    }

    /** 未知字段保留编号及值并标记 PARTIAL；不能静默丢弃后声称完全解码。 */
    @Test
    void testUnknownField_ShouldRetainWireValue() throws Exception {
        byte[] packet = HexFormat.of().parseHex("00000001000c0000000411150600");
        var result = new GamePacketDecoder(catalog(), mapper).decode(packet, "SC");
        assertEquals("PARTIAL", result.path("status").asText());
        assertEquals(3, result.path("body").path("$field_2").asInt());
        assertEquals(1, result.path("warnings").size());
    }

    /** WebSocket 掩码和 continuation 必须先重组，后续压缩消息复用同方向字典。 */
    @Test
    void testContextTakeoverAndFragments_ShouldKeepDictionaryAcrossMessages() throws Exception {
        byte[] original =
                "repeatable-packet-contents-repeatable-packet-contents"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Deflater compressor = new Deflater(6, true);
        try (WebSocketStreamDecoder stream = new WebSocketStreamDecoder(false)) {
            for (int round = 0; round < 2; round++) {
                compressor.setInput(original);
                byte[] buffer = new byte[1024];
                int count = compressor.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
                byte[] compressed = Arrays.copyOf(buffer, count - 4);
                int half = compressed.length / 2;
                byte[] first = frame(0x42, Arrays.copyOf(compressed, half), true);
                byte[] last =
                        frame(0x80, Arrays.copyOfRange(compressed, half, compressed.length), false);
                assertNull(stream.accept(first));
                assertArrayEquals(original, stream.accept(last));
            }
        } finally {
            compressor.end();
        }
    }

    private byte[] frame(int flags, byte[] payload, boolean mask) {
        ByteBuffer result = ByteBuffer.allocate(payload.length + (mask ? 6 : 2));
        result.put((byte) flags).put((byte) (payload.length | (mask ? 128 : 0)));
        if (mask) {
            result.put(new byte[] {1, 2, 3, 4});
        }
        for (int index = 0; index < payload.length; index++) {
            result.put((byte) (payload[index] ^ (mask ? (index % 4 + 1) : 0)));
        }
        return result.array();
    }
}
