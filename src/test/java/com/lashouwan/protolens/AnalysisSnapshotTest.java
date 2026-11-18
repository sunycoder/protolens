package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** 操作证据快照独立于实时淘汰，保留原编号与字典，不中止源连接。 */
class AnalysisSnapshotTest {
    @TempDir Path directory;

    @Test
    void testFrozenRange_ShouldSurviveEvictionAndRestartWithoutRenumbering() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProtocolCatalog catalog =
                new ProtocolCatalog(
                        mapper.readTree(
                                "{\"schemaVersion\":1,\"messages\":{},\"definitions\":{}}"));
        CaptureStore store = new CaptureStore(directory, mapper, catalog);
        store.configureLive(100, 1024 * 1024);
        String source = store.createLive("a", "https://example.test/websocket", "");
        for (int index = 0; index < 100; index++) {
            store.appendLive(
                    source,
                    mapper.createObjectNode()
                            .put("direction", "CS")
                            .put("status", "DECODED")
                            .put("protocolId", index)
                            .put("connection", "a"));
        }
        var summary = store.snapshotRange(source, "建造霹雳车", 20, 30);
        String id = summary.path("id").asText();
        assertEquals(11, summary.path("messageCount").asInt());
        assertTrue(summary.path("analysisSnapshot").asBoolean());
        for (int index = 100; index < 250; index++) {
            store.appendLive(
                    source,
                    mapper.createObjectNode().put("status", "DECODED").put("connection", "a"));
        }
        assertThrows(IllegalArgumentException.class, () -> store.detail(source, 20));
        assertThrows(
                IllegalArgumentException.class, () -> store.snapshotRange(source, "已淘汰", 20, 30));
        assertEquals("CAPTURING", store.describe(source).path("liveState").asText());
        CaptureStore restored = new CaptureStore(directory, mapper, catalog);
        assertEquals(20, restored.detail(id, 20).path("protocolId").asInt());
        assertEquals(30, restored.detail(id, 30).path("index").asInt());
        assertEquals(
                11, restored.describe(id).path("connections").path(0).path("messages").asInt());
        assertEquals(
                source, restored.describe(id).path("snapshotSource").path("captureId").asText());
    }
}
