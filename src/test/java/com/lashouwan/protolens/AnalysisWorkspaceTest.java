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
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 分析工作台的真实仓库契约：精确过滤、采集中快照、落盘恢复及并发查询。 */
class AnalysisWorkspaceTest {
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

    private ObjectNode message(int protocolId, String name, String connection, String direction) {
        ObjectNode message =
                mapper.createObjectNode()
                        .put("protocolId", protocolId)
                        .put("protocolName", name)
                        .put("connection", connection)
                        .put("direction", direction)
                        .put("status", "DECODED");
        message.putObject("body").put("amount", protocolId);
        return message;
    }

    private MessageQuery query(String include, String exclude) {
        return new MessageQuery(
                "", "", "", "", false, 0, Integer.MAX_VALUE, 0, 100, "asc", include, exclude);
    }

    @Test
    void exactShortcutsMustNotMatchIdOrNamePrefixes() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        store.appendLive(id, message(312, "GcUpdateMore", "a", "SC"));
        store.appendLive(id, message(0, "GcZero", "a", "SC"));
        assertEquals(1, store.messages(id, query("id:12", "")).path("total").asInt());
        assertEquals(1, store.messages(id, query("name:GcUpdate", "")).path("total").asInt());
        assertEquals(2, store.messages(id, query("12", "")).path("total").asInt());
        assertEquals(1, store.messages(id, query("id:12,id:312", "id:12")).path("total").asInt());
        assertEquals(1, store.messages(id, query("id:0", "")).path("total").asInt());
    }

    @Test
    void snapshotMustKeepSourceRunningAndPersistContextAndOriginalIndices() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        for (int i = 0; i < 105; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        var frozen = store.snapshotCurrent(id, "当前快照");
        String frozenId = frozen.path("id").asText();
        assertEquals(5, frozen.path("firstIndex").asInt());
        store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        assertEquals(105, store.describe(id).path("lastIndex").asInt());
        assertEquals("CAPTURING", store.describe(id).path("liveState").asText());
        assertEquals(104, store().describe(frozenId).path("lastIndex").asInt());
        var operation = store.snapshotRange(id, "捐献一次", 100, 104, "检查资源数量变化");
        assertEquals("检查资源数量变化", operation.path("operationContext").asText());
        assertThrows(IllegalArgumentException.class, () -> store.snapshotRange(id, "已淘汰", 0, 3));
    }

    @Test
    void previousMessageMustRespectProtocolDirectionAndConnection() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        store.appendLive(id, message(12, "GcUpdate", "b", "SC"));
        store.appendLive(id, message(12, "GcUpdate", "a", "CS"));
        store.appendLive(id, message(312, "GcOther", "a", "SC"));
        store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        assertEquals(0, store.previousSameProtocol(id, 4).path("index").asInt());
        assertThrows(IllegalArgumentException.class, () -> store.previousSameProtocol(id, 0));
        assertThrows(IllegalArgumentException.class, () -> store.previousSameProtocol(id, 999));
    }

    @Test
    void continuousArchiveMustSurviveMemoryEvictionRestartAndTruncatedTail() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        for (int i = 0; i < 105; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        store.enableContinuous(id);
        for (int i = 0; i < 150; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        assertEquals(100, store.describe(id).path("messageCount").asInt());
        var recovered = store().read(id);
        assertEquals(250, recovered.path("messages").size());
        assertEquals(5, recovered.path("messages").path(0).path("index").asInt());
        assertEquals(254, recovered.path("messages").path(249).path("index").asInt());
        assertEquals("INTERRUPTED", recovered.path("liveState").asText());
        Files.writeString(directory.resolve(id + ".journal"), "{broken", StandardOpenOption.APPEND);
        assertTrue(store().read(id).has("recoveryWarning"));
        assertEquals(250, store().read(id).path("messages").size());
    }

    @Test
    void stopAndSaveMustNotOverwriteEarlierDiskMessagesWithMemoryWindow() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        store.enableContinuous(id);
        for (int i = 0; i < 220; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        store.stopLive(id, "STOPPED");
        assertEquals("STOPPED", store().read(id).path("liveState").asText());
        store.persistLive(id, "完整保存");
        assertEquals(220, store().read(id).path("messages").size());
        assertEquals(220, store().list().path(0).path("messageCount").asInt());
        assertFalse(Files.exists(directory.resolve(id + ".journal")));
    }

    @Test
    void clearingTemporaryMessagesMustNotClearContinuousArchive() throws Exception {
        CaptureStore store = store();
        String persisted = store.createLive("a", "test", "");
        String temporary = store.createLive("b", "test", "");
        store.appendLive(persisted, message(12, "GcUpdate", "a", "SC"));
        store.appendLive(temporary, message(12, "GcUpdate", "b", "SC"));
        store.enableContinuous(persisted);
        var frozen = store.snapshotCurrent(persisted, "独立快照");
        assertFalse(frozen.has("diskFirstIndex"));
        assertFalse(frozen.path("continuous").asBoolean());
        var cleared = store.clearTemporary(Set.of(persisted, temporary));
        assertEquals(Set.of(temporary), cleared);
        assertEquals(1, store.describe(persisted).path("messageCount").asInt());
        assertEquals(0, store.describe(temporary).path("messageCount").asInt());
    }

    @Test
    void diskFailureMustKeepCaptureRunningAndReportLastSavedBoundary() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        store.enableContinuous(id);
        Files.createDirectory(directory.resolve(id + ".journal"));
        store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        var state = store.describe(id);
        assertEquals("CAPTURING", state.path("liveState").asText());
        assertFalse(state.path("continuous").asBoolean());
        assertTrue(state.has("continuousError"));
        assertEquals(-1, state.path("diskLastIndex").asInt());
        assertEquals(1, state.path("messageCount").asInt());
        assertThrows(IllegalArgumentException.class, () -> store.enableContinuous(id));
    }

    @Test
    void concurrentQueriesMustUseStableViewsWhileCaptureEvicts() throws Exception {
        CaptureStore store = store();
        String id = store.createLive("a", "test", "");
        for (int i = 0; i < 100; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        var reader =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                for (int i = 0; i < 100; i++) {
                                    var page = store.messages(id, query("id:12", ""));
                                    assertEquals(
                                            page.path("capture").path("messageCount").asInt(),
                                            page.path("total").asInt());
                                    assertTrue(page.path("queryMs").asDouble() >= 0);
                                    assertEquals(100, page.path("items").size());
                                }
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        });
        for (int i = 0; i < 200; i++) store.appendLive(id, message(12, "GcUpdate", "a", "SC"));
        reader.get();
    }
}
