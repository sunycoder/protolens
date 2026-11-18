package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.MessageQuery;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/** 缓存淘汰、稳定编号、排序过滤和持久化删除的仓库级边界，不向游戏发送消息。 */
class CaptureRetentionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private CaptureStore store() throws Exception {
        ObjectNode dictionary = mapper.createObjectNode().put("schemaVersion", 1);
        dictionary.putObject("messages");
        dictionary.putObject("definitions");
        CaptureStore store = new CaptureStore(directory, mapper, new ProtocolCatalog(dictionary));
        store.configureLive(100, 1024 * 1024);
        return store;
    }

    private ObjectNode message(int value) {
        ObjectNode message =
                mapper.createObjectNode()
                        .put("protocolName", value % 2 == 0 ? "CgDonate" : "GcMap")
                        .put("protocolId", value % 2 == 0 ? 2364 : 301)
                        .put("direction", "CS")
                        .put("status", "DECODED");
        message.putObject("body").put("value", value);
        return message;
    }

    @Test
    void testEviction_ShouldKeepStableIndicesAndFilterBeforePaging() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "https://example.test/websocket", "");
        for (int value = 0; value < 105; value++) {
            store.appendLive(id, message(value));
        }
        ObjectNode capture = store.read(id);
        assertEquals(100, capture.path("messages").size());
        assertEquals(5, capture.path("messages").path(0).path("index").asInt());
        assertThrows(IllegalArgumentException.class, () -> store.detail(id, 0));
        assertEquals(104, store.detail(id, 104).path("body").path("value").asInt());
        var reversed =
                store.messages(
                        id,
                        new MessageQuery("", "", "", "", false, 0, 9999, -1, 10, "desc", "", ""));
        assertEquals(104, reversed.path("items").path(0).path("index").asInt());
        assertEquals(95, reversed.path("items").path(9).path("index").asInt());
        assertEquals(5, reversed.path("capture").path("evictedCount").asInt());
        var filtered =
                store.messages(
                        id,
                        new MessageQuery(
                                "",
                                "CS",
                                "DECODED",
                                "",
                                false,
                                0,
                                9999,
                                0,
                                3,
                                "desc",
                                "Donate,Map",
                                "301"));
        assertEquals(50, filtered.path("total").asInt());
        assertEquals(104, filtered.path("items").path(0).path("index").asInt());
        assertEquals(100, filtered.path("items").path(2).path("index").asInt());
        assertTrue(filtered.path("items").path(0).path("preview").asText().contains("value=104"));
        assertFalse(filtered.path("items").path(0).has("body"));
        var tail =
                store.messages(
                        id, new MessageQuery("", "", "", "", false, 0, 9999, -1, 3, "asc", "", ""));
        assertEquals(102, tail.path("items").path(0).path("index").asInt());
    }

    @Test
    void testByteBudget_ShouldEvictOldLargeMessagesWithoutRenumbering() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "https://example.test/websocket", "");
        for (int value = 0; value < 15; value++) {
            store.appendLive(id, message(value).put("packetHex", "f".repeat(100000)));
        }
        var page =
                store.messages(
                        id,
                        new MessageQuery("", "", "", "", false, 0, 9999, 0, 100, "asc", "", ""));
        assertTrue(page.path("total").asInt() < 15);
        assertTrue(page.path("capture").path("cacheBytes").asLong() <= 1024 * 1024);
        assertEquals(14, store.detail(id, 14).path("body").path("value").asInt());
    }

    @Test
    void testTemporarySaveAndDelete_ShouldNeverPersistImplicitlyOrDeleteActiveRecord()
            throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "https://example.test/websocket", "");
        store.appendLive(id, message(1));
        store.mark(id, "操作", 0, 0);
        assertThrows(IllegalArgumentException.class, () -> store.delete(id));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        store.stopLive(id, "STOPPED");
        assertEquals(1, store.list().size());
        String name = store.list().path(0).path("name").asText();
        assertTrue(name.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        store.persistLive(id, "");
        assertEquals(name, store.read(id).path("name").asText());
        store.delete(id);
        assertTrue(store.list().isEmpty());
        assertThrows(Exception.class, () -> store.detail(id, 0));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        assertThrows(IllegalArgumentException.class, () -> store.delete("../outside"));
    }

    @Test
    void testEvictedMarkAndSavedIndices_ShouldKeepOriginalNumberingAfterRestart() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "https://example.test/websocket", "");
        store.appendLive(id, message(0));
        store.mark(id, "开头", 0, 0);
        for (int value = 1; value < 103; value++) {
            store.appendLive(id, message(value));
        }
        assertTrue(store.read(id).path("marks").path(0).path("expired").asBoolean());
        store.stopLive(id, "CLOSED");
        store.persistLive(id, "");
        CaptureStore restored = store();
        assertEquals(102, restored.detail(id, 102).path("index").asInt());
        assertThrows(IllegalArgumentException.class, () -> restored.detail(id, 0));
        assertEquals(100, restored.list().path(0).path("messageCount").asInt());
    }
}
