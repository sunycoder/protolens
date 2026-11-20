package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** 分析成果和抓包一起恢复；非法导入与冲突不能覆盖原数据。 */
class AnalysisProjectTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private CaptureStore store() throws Exception {
        return new CaptureStore(
                directory,
                mapper,
                new ProtocolCatalog(
                        mapper.readTree(
                                "{\"schemaVersion\":1,\"messages\":{\"7\":{\"name\":\"GcSynthetic\"}},\"definitions\":{\"7\":{}}}")));
    }

    private String record(CaptureStore store) throws Exception {
        String id = store.createLive("synthetic", "https://example.test/websocket", "");
        for (int index = 0; index < 3; index++) {
            ObjectNode packet =
                    mapper.createObjectNode()
                            .put("protocolId", 7)
                            .put("protocolName", "GcSynthetic")
                            .put("direction", "SC")
                            .put("connection", "synthetic")
                            .put("status", "DECODED");
            packet.putObject("body").put("amount", index);
            store.appendLive(id, packet);
        }
        return id;
    }

    private ObjectNode notes(CaptureStore store, String id) throws Exception {
        ObjectNode result = store.research(id);
        result.withArray("notes")
                .addObject()
                .put("index", 1)
                .put("path", "/amount")
                .put("text", "已验证的合成字段")
                .put("kind", "已验证");
        result.withArray("columns").addObject().put("path", "/amount").put("label", "数量");
        result.withObject("widths").put("protocolName", 300);
        return result;
    }

    /** 同一成果更新必须基于最新 revision，拒绝冲突不改变已保存输入。 */
    @Test
    void conflictMustKeepThePreviouslySavedDocument() throws Exception {
        var store = store();
        String id = record(store);
        ObjectNode draft = notes(store, id);
        var saved = store.saveResearch(id, draft);
        assertEquals(1, saved.path("revision").asInt());
        assertThrows(IllegalArgumentException.class, () -> store.saveResearch(id, draft));
        assertEquals("已验证的合成字段", store.research(id).path("notes").path(0).path("text").asText());
    }

    /** 快照只携带所属原区间批注；导出再导入保留字典、原编号和成果，并创建新ID。 */
    @Test
    void snapshotAndProjectRoundTripMustPreserveEvidenceAndNotes() throws Exception {
        var store = store();
        String id = record(store);
        ObjectNode draft = notes(store, id);
        store.saveResearch(id, draft);
        var snapshot = store.snapshotRange(id, "合成区间", 1, 2);
        String frozenId = snapshot.path("id").asText();
        assertEquals(1, store.research(frozenId).path("notes").size());
        var empty = store.snapshotRange(id, "另一区间", 2, 2);
        assertEquals(0, store.research(empty.path("id").asText()).path("notes").size());
        var exported = store.exportProject(frozenId);
        var imported = store.importProject(exported);
        String importedId = imported.path("id").asText();
        assertNotEquals(frozenId, importedId);
        assertFalse(imported.path("temporary").asBoolean());
        assertEquals(
                store.read(frozenId).path("dictionary"), store.read(importedId).path("dictionary"));
        assertEquals(1, store.read(importedId).path("messages").path(0).path("index").asInt());
        assertEquals(
                "数量", store.research(importedId).path("columns").path(0).path("label").asText());
        assertEquals(300, store.research(importedId).path("widths").path("protocolName").asInt());
        var reopened = store();
        assertEquals(1, reopened.research(importedId).path("notes").size());
    }

    /** 导入不能读取任意路径或重复原编号；拒绝非法批注时原记录仍可读取。 */
    @Test
    void invalidProjectMustNotOverwriteExistingCapture() throws Exception {
        var store = store();
        String id = record(store);
        store.saveResearch(id, notes(store, id));
        var exported = store.exportProject(id);
        ((ObjectNode) exported.path("capture").path("messages").path(1)).put("index", 0);
        assertThrows(IllegalArgumentException.class, () -> store.importProject(exported));
        assertThrows(IllegalArgumentException.class, () -> store.research("../secret"));
        var invalid = store.research(id);
        ((ObjectNode) invalid.path("notes").path(0)).put("text", "a".repeat(501));
        assertThrows(IllegalArgumentException.class, () -> store.saveResearch(id, invalid));
        assertEquals(3, store.read(id).path("messages").size());
    }
}
