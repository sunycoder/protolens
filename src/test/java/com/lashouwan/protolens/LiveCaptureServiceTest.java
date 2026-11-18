package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.capture.MessageQuery;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.Deflater;

/** 实时入口的顺序、重试、持久化与生命周期验证；全部使用已有帧，不发送游戏请求。 */
class LiveCaptureServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ProtocolCatalog catalog() throws Exception {
        return new ProtocolCatalog(
                mapper.readTree(
                        """
{"schemaVersion":1,"messages":{"2364":{"name":"CgAllianceTechDonate","direction":"CS"}},
"definitions":{"2364":{"1":["techGroupId",8],"2":["isUseCp",2],"3":["level",8]}}}
"""));
    }

    private ObjectNode open(long sequence) {
        return mapper.createObjectNode()
                .put("sequence", sequence)
                .put("type", "open")
                .put("connection", "100")
                .put("endpoint", "https://example.test/websocket1?token=do-not-copy")
                .put("extensions", "permessage-deflate; client_no_context_takeover");
    }

    private ObjectNode frame(long sequence) {
        byte[] payload = HexFormat.of().parseHex("62606208e4b46160606017fdc42f24cac10000");
        byte[] wire =
                ByteBuffer.allocate(payload.length + 2)
                        .put((byte) 0xc2)
                        .put((byte) payload.length)
                        .put(payload)
                        .array();
        return mapper.createObjectNode()
                .put("sequence", sequence)
                .put("type", "frame")
                .put("connection", "100")
                .put("direction", "CS")
                .put("frameId", "1")
                .put("time", "2026-11-09T12:33:43+08:00")
                .put("wireBase64", Base64.getEncoder().encodeToString(wire));
    }

    private ObjectNode batch(String source, JsonNode... events) {
        ObjectNode batch = mapper.createObjectNode().put("source", source);
        for (JsonNode event : events) {
            batch.withArray("events").add(event);
        }
        return batch;
    }

    /** 重试不重复解码；默认临时记录不落盘，选择保存后才可在新仓库实例恢复。 */
    @Test
    void testRetry_ShouldDeduplicateAndOnlyPersistOnExplicitSave() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {});
        service.start();
        String source = UUID.randomUUID().toString();
        ObjectNode request = batch(source, open(1), frame(2));
        var status = service.ingest(request);
        String id = status.path("connections").path(0).path("captureId").asText();
        assertEquals(2, status.path("acknowledged").asInt());
        service.ingest(request);
        assertEquals(2, service.status().path("duplicates").asInt());
        assertEquals(1, store.read(id).path("messages").size());
        assertEquals(1017, store.detail(id, 0).path("body").path("techGroupId").asInt());
        CaptureStore restored = new CaptureStore(directory, mapper, catalog());
        assertTrue(restored.list().isEmpty());
        assertFalse(store.read(id).path("connections").toString().contains("do-not-copy"));
        assertThrows(IllegalArgumentException.class, () -> store.persistLive(id, "未停止"));
        service.stop(true, "一次捐献");
        assertFalse(Files.exists(directory.resolve(id + ".journal")));
        assertEquals(
                "STOPPED",
                new CaptureStore(directory, mapper, catalog()).read(id).path("liveState").asText());
        assertEquals("一次捐献", restored.read(id).path("name").asText());
    }

    /** 缺失事件必须拒绝，随后补齐可继续；重试相同序号但修改内容必须拒绝。 */
    @Test
    void testGapAndConflictingRetry_ShouldRejectWithoutAdvancingDecoder() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            String source = UUID.randomUUID().toString();
            service.ingest(batch(source, open(1)));
            assertThrows(
                    IllegalArgumentException.class, () -> service.ingest(batch(source, frame(3))));
            assertEquals(1, service.status().path("acknowledged").asInt());
            service.ingest(batch(source, frame(2)));
            var changed = frame(2).put("frameId", "changed");
            assertThrows(
                    IllegalArgumentException.class, () -> service.ingest(batch(source, changed)));
            assertEquals(1, service.status().path("connections").path(0).path("messages").asInt());
        }
    }

    /** 未开始时不接受帧；缺握手不能借用其他连接的 inflater，停止后也不能继续采集。 */
    @Test
    void testLifecycle_ShouldRequireStartAndHandshake() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            String source = UUID.randomUUID().toString();
            assertThrows(
                    IllegalArgumentException.class, () -> service.ingest(batch(source, open(1))));
            service.start();
            service.ingest(batch(source, frame(1)));
            assertTrue(service.status().path("lastError").asText().contains("握手"));
            assertTrue(store.list().isEmpty());
            service.stop();
            assertThrows(
                    IllegalArgumentException.class, () -> service.ingest(batch(source, frame(2))));
        }
    }

    /** 整个批次必须先验证，后续非法 Base64 不能导致前面的 open 已经提交。 */
    @Test
    void testMalformedBatch_ShouldNotApplyEarlierEvents() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.ingest(
                                    batch(
                                            UUID.randomUUID().toString(),
                                            open(1),
                                            frame(2).put("wireBase64", "not base64!!!"))));
            assertEquals(0, service.status().path("acknowledged").asInt());
            assertTrue(store.list().isEmpty());
        }
    }

    /** 标记不触发持久化；停止后显式保存应同时保留标记和后续消息。 */
    @Test
    void testLiveMark_ShouldPreserveLaterFramesAndTail() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            String source = UUID.randomUUID().toString();
            String id =
                    service.ingest(batch(source, open(1), frame(2)))
                            .path("connections")
                            .path(0)
                            .path("captureId")
                            .asText();
            store.mark(id, "捐献", 0, 0);
            service.ingest(batch(source, frame(3)));
            var tail =
                    store.messages(
                            id,
                            new MessageQuery(
                                    "Donate",
                                    "",
                                    "",
                                    "",
                                    true,
                                    0,
                                    Integer.MAX_VALUE,
                                    -1,
                                    1,
                                    "asc",
                                    "",
                                    ""));
            assertEquals(2, tail.path("total").asInt());
            assertEquals(1, tail.path("items").path(0).path("index").asInt());
            service.stop();
            assertTrue(new CaptureStore(directory, mapper, catalog()).list().isEmpty());
            store.persistLive(id, "");
            var recovered = new CaptureStore(directory, mapper, catalog()).read(id);
            assertEquals(2, recovered.path("messages").size());
            assertEquals(1, recovered.path("marks").size());
        }
    }

    /** 支持已经由采集器解压的完整消息；不能把它伪装成保留了 WebSocket 原始 wire 帧。 */
    @Test
    void testCompletePacket_ShouldDecodeWithoutPretendingWireBytes() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            byte[] packet = HexFormat.of().parseHex("00020051093c0000000715f20f12150800");
            ObjectNode event =
                    mapper.createObjectNode()
                            .put("sequence", 2)
                            .put("type", "packet")
                            .put("connection", "100")
                            .put("direction", "CS")
                            .put("time", "2026-11-09T12:33:50+08:00")
                            .put("packetBase64", Base64.getEncoder().encodeToString(packet));
            String id =
                    service.ingest(batch(UUID.randomUUID().toString(), open(1), event))
                            .path("connections")
                            .path(0)
                            .path("captureId")
                            .asText();
            JsonNode detail = store.detail(id, 0);
            assertEquals(1017, detail.path("body").path("techGroupId").asInt());
            assertEquals("DECOMPRESSED_MESSAGE", detail.path("inputFormat").asText());
            assertEquals("", detail.path("wireHex").asText());
            assertEquals(0, detail.path("wireBytes").asInt());
        }
    }

    /** 同一采集轮次的活动和已关闭记录一并清空，其他轮次、已保存记录及独立快照保留。 */
    @Test
    void testClearSession_ShouldKeepSavedHistoryAndEarlierRounds() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            String oldSource = UUID.randomUUID().toString();
            String older =
                    service.ingest(batch(oldSource, open(1), frame(2)))
                            .path("connections")
                            .get(0)
                            .path("captureId")
                            .asText();
            service.stop();
            service.start();
            String source = UUID.randomUUID().toString();
            String active =
                    service.ingest(batch(source, open(1), frame(2)))
                            .path("connections")
                            .get(0)
                            .path("captureId")
                            .asText();
            String snapshot = store.snapshotRange(active, "独立快照", 0, 0).path("id").asText();
            service.ingest(
                    batch(
                            source,
                            open(3).put("connection", "200"),
                            frame(4).put("connection", "200")));
            String closed = service.status().path("connections").get(1).path("captureId").asText();
            service.ingest(
                    batch(
                            source,
                            mapper.createObjectNode()
                                    .put("sequence", 5)
                                    .put("type", "close")
                                    .put("connection", "200")));
            service.ingest(
                    batch(
                            source,
                            open(6).put("connection", "300"),
                            frame(7).put("connection", "300")));
            String saved = service.status().path("connections").get(1).path("captureId").asText();
            service.ingest(
                    batch(
                            source,
                            mapper.createObjectNode()
                                    .put("sequence", 8)
                                    .put("type", "close")
                                    .put("connection", "300")));
            service.saveCapture(saved, "已保存记录");
            assertEquals(2, service.status().path("sessionRecordCount").asInt());
            var result = service.clearSession();
            assertEquals(2, result.path("clearedCaptureIds").size());
            assertTrue(result.path("enabled").asBoolean());
            assertEquals(8, result.path("acknowledged").asInt());
            assertTrue(store.read(active).path("messages").isEmpty());
            assertTrue(store.read(closed).path("messages").isEmpty());
            assertEquals(1, store.read(older).path("messages").size());
            assertEquals(1, store.read(saved).path("messages").size());
            assertEquals(1, store.read(snapshot).path("messages").size());
            assertEquals(2, service.clearSession().path("clearedCaptureIds").size());
            assertEquals(0, service.status().path("sessionMessageCount").asInt());
        }
    }

    /** 清空不重置传输去重与压缩字典；第二帧依赖第一帧的 context takeover，必须仍能解码。 */
    @Test
    void testClearSession_ShouldContinueCompressionAndIgnoreOldRetries() throws Exception {
        CaptureStore store = new CaptureStore(directory, mapper, catalog());
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog(), mapper, ignored -> {})) {
            service.start();
            String source = UUID.randomUUID().toString();
            byte[] packet = HexFormat.of().parseHex("00020051093c0000000715f20f12150800");
            Deflater compressor = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            try {
                ObjectNode first = contextFrame(compressor, packet, 2);
                String old =
                        service.ingest(
                                        batch(
                                                source,
                                                open(1).put("extensions", "permessage-deflate"),
                                                first))
                                .path("connections")
                                .get(0)
                                .path("captureId")
                                .asText();
                service.clearSession();
                service.ingest(batch(source, first));
                assertTrue(store.read(old).path("messages").isEmpty());
                assertEquals(1, store.list().size());
                assertEquals(2, service.status().path("acknowledged").asInt());
                var state = service.ingest(batch(source, contextFrame(compressor, packet, 3)));
                String fresh = state.path("connections").get(0).path("captureId").asText();
                assertEquals(old, fresh);
                assertEquals(1, store.read(fresh).path("clearedCount").asInt());
                assertEquals(1, store.read(fresh).path("clearVersion").asInt());
                assertEquals(1017, store.detail(fresh, 1).path("body").path("techGroupId").asInt());
                assertEquals("DECODED", store.detail(fresh, 1).path("status").asText());
            } finally {
                compressor.end();
            }
        }
    }

    private ObjectNode contextFrame(Deflater compressor, byte[] packet, long sequence) {
        compressor.setInput(packet);
        byte[] buffer = new byte[1024];
        int length = compressor.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH) - 4;
        byte[] wire =
                ByteBuffer.allocate(length + 2)
                        .put((byte) 0xc2)
                        .put((byte) length)
                        .put(buffer, 0, length)
                        .array();
        return frame(sequence).put("wireBase64", Base64.getEncoder().encodeToString(wire));
    }
}
