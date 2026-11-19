package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** 只用合成协议验证自身份、连接隔离、切号分段和快照/日志持久化，不包含真实账号凭证。 */
class ConnectionIdentityTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ProtocolCatalog catalog() throws Exception {
        return new ProtocolCatalog(
                mapper.readTree(
                        """
{"schemaVersion":1,"messages":{"1":{"name":"CgLogin"},"100":{"name":"GcPlayerInfo"},"200":{"name":"GcOtherPlayerInfo"}},
"definitions":{"1":{"1":["uid",10],"2":["roleId",10]},"100":{"1":["playerInfo",[12,"PsPlayerInfo"]]},
"PsPlayerInfo":{"1":["roleId",10],"2":["name",11],"23":["serverId",8],"50":["accId",10]}}}
"""));
    }

    private CaptureStore store() throws Exception {
        return new CaptureStore(directory, mapper, catalog());
    }

    private ObjectNode player(String connection, String role, String name) {
        ObjectNode packet =
                mapper.createObjectNode()
                        .put("connection", connection)
                        .put("protocolId", 100)
                        .put("protocolName", "GcPlayerInfo")
                        .put("direction", "SC")
                        .put("status", "DECODED");
        packet.putObject("body")
                .putObject("playerInfo")
                .put("roleId", role)
                .put("name", name)
                .put("serverId", 138)
                .put("accId", "9007199254740995")
                .put("loginToken", "DO_NOT_COPY");
        return packet;
    }

    private ObjectNode login(String connection) {
        ObjectNode packet =
                mapper.createObjectNode()
                        .put("connection", connection)
                        .put("protocolId", 1)
                        .put("protocolName", "CgLogin")
                        .put("direction", "CS")
                        .put("status", "DECODED");
        packet.putObject("body").put("uid", "123").put("roleId", "456").put("sign", "DO_NOT_COPY");
        return packet;
    }

    @Test
    void sameUrlConnectionsMustHaveIndependentIdentitiesAndNotes() throws Exception {
        var store = store();
        String a = store.createLive("a", "https://same.test/websocket", "");
        String b = store.createLive("b", "https://same.test/websocket", "");
        store.appendLive(a, player("a", "9007199254740993", "甲"));
        store.appendLive(b, player("b", "9007199254740994", "乙"));
        store.annotateConnection(a, "a", "主号");
        var ca = store.describe(a).path("connections").path(0);
        var cb = store.describe(b).path("connections").path(0);
        assertEquals("9007199254740993", ca.path("identity").path("roleId").asText());
        assertEquals("9007199254740995", ca.path("identity").path("accountId").asText());
        assertEquals("主号", ca.path("note").asText());
        assertFalse(cb.has("note"));
        assertEquals("乙", cb.path("identity").path("nickname").asText());
        assertFalse(ca.toString().contains("DO_NOT_COPY"));
        assertThrows(IllegalArgumentException.class, () -> store.annotateConnection(a, "b", "不串号"));
    }

    @Test
    void mustRejectOtherPlayersInvalidIdentityFieldsWrongDirectionAndUnknownDictionary()
            throws Exception {
        var store = store();
        String a = store.createLive("a", "test", "");
        store.appendLive(
                a,
                player("a", "1", "他人")
                        .put("protocolId", 200)
                        .put("protocolName", "GcOtherPlayerInfo"));
        var invalid = player("a", "1", "未知").put("status", "PARTIAL");
        ((ObjectNode) invalid.path("body").path("playerInfo")).remove("roleId");
        ((ObjectNode) invalid.path("body").path("playerInfo"))
                .put("$field_1", "wire-type-mismatch");
        store.appendLive(a, invalid);
        store.appendLive(a, player("a", "1", "客户端伪装").put("direction", "CS"));
        assertFalse(store.describe(a).path("connections").path(0).has("identity"));
        var wrong = mapper.createObjectNode().put("schemaVersion", 1);
        wrong.putObject("messages").putObject("100").put("name", "GcPlayerInfo");
        wrong.putObject("definitions");
        var incompatible =
                new CaptureStore(
                        directory.resolve("incompatible"), mapper, new ProtocolCatalog(wrong));
        String id = incompatible.createLive("a", "test", "");
        incompatible.appendLive(id, player("a", "1", "未知"));
        assertFalse(incompatible.describe(id).path("connections").path(0).has("identity"));
    }

    @Test
    void unrelatedUnknownFieldsMustNotHideVerifiedPlayerIdentity() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(id, login("a"));
        var partial = player("a", "9007199254740993", "甲").put("status", "PARTIAL");
        partial.putArray("warnings").add("未知字段 26，按 wire 类型保留").add("未知字段 27，按 wire 类型保留");
        ((ObjectNode) partial.path("body")).put("$field_26", 1).put("$field_27", 2);
        store.appendLive(id, partial);
        var identity = store.describe(id).path("connections").path(0).path("identity");
        assertEquals("identified", identity.path("state").asText());
        assertEquals("9007199254740993", identity.path("roleId").asText());
        assertEquals("PARTIAL", store.detail(id, 1).path("status").asText());
        assertEquals(2, store.detail(id, 1).path("warnings").size());
    }

    @Test
    void reidentifyMustRepairAlreadyCapturedPendingMetadataWithoutNewConnection() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(id, login("a"));
        var message = player("a", "99", "甲").put("status", "PARTIAL");
        store.appendLive(id, message);
        store.stopLive(id, "STOPPED");
        store.persistLive(id, "旧识别规则保存的记录");
        // 模拟旧版本保留了 PARTIAL 消息、元数据却只有 CgLogin 待确认的真实失败形态。
        var file = directory.resolve(id + ".json");
        var record = (ObjectNode) mapper.readTree(file.toFile());
        var connection = (ObjectNode) record.path("connections").path(0);
        var pending = connection.path("identityHistory").path(0).deepCopy();
        connection.set("identity", pending);
        connection.putArray("identityHistory").add(pending);
        mapper.writeValue(file.toFile(), record);
        var reopened = store();
        var repaired = reopened.reidentifyConnections(id);
        assertEquals(id, repaired.path("id").asText());
        assertEquals(2, repaired.path("messageCount").asInt());
        assertEquals(
                "99",
                repaired.path("connections").path(0).path("identity").path("roleId").asText());
        assertEquals(2, repaired.path("connections").path(0).path("identityHistory").size());
        assertEquals(
                2,
                reopened.reidentifyConnections(id)
                        .path("connections")
                        .path(0)
                        .path("identityHistory")
                        .size());
        assertEquals(
                "99",
                store().read(id)
                        .path("connections")
                        .path(0)
                        .path("identity")
                        .path("roleId")
                        .asText());
    }

    @Test
    void switchingMustClearOldNotesAndPreserveMessageAndSnapshotIdentity() throws Exception {
        var store = store();
        String a = store.createLive("a", "test", "");
        store.appendLive(a, player("a", "1", "甲"));
        store.annotateConnection(a, "a", "主号");
        var expected = store.describe(a).path("connections").path(0).path("identity").deepCopy();
        store.appendLive(a, login("a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.annotateConnection(a, "a", "过期编辑", expected));
        assertEquals("", store.describe(a).path("connections").path(0).path("note").asText());
        assertEquals(
                "pending", store.detail(a, 1).path("connectionIdentity").path("state").asText());
        store.appendLive(a, player("a", "2", "乙"));
        assertEquals("1", store.detail(a, 0).path("connectionIdentity").path("roleId").asText());
        assertEquals("2", store.detail(a, 2).path("connectionIdentity").path("roleId").asText());
        var snap = store.snapshotRange(a, "甲的片段", 0, 0);
        assertEquals(
                "1", snap.path("connections").path(0).path("identity").path("roleId").asText());
        assertEquals(1, snap.path("connections").path(0).path("identityHistory").size());
        store.appendLive(a, player("a", "2", "乙"));
        assertEquals(
                3, store.describe(a).path("connections").path(0).path("identityHistory").size());
        String reconnect = store.createLive("new", "test", "");
        assertFalse(store.describe(reconnect).path("connections").path(0).has("identity"));
    }

    @Test
    void continuousReplayMustRestoreIdentityAndOnlyCurrentNotes() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.enableContinuous(id);
        store.appendLive(id, player("a", "1", "甲"));
        store.annotateConnection(id, "a", "主号");
        assertEquals("主号", store().read(id).path("connections").path(0).path("note").asText());
        store.appendLive(id, login("a"));
        store.appendLive(id, player("a", "2", "乙"));
        var restored = store().read(id).path("connections").path(0);
        assertEquals("2", restored.path("identity").path("roleId").asText());
        assertNotEquals("主号", restored.path("note").asText());
        store.annotateConnection(id, "a", "小号");
        store.stopLive(id, "STOPPED");
        assertEquals("小号", store().read(id).path("connections").path(0).path("note").asText());
    }

    @Test
    void invalidNotesMustLeaveOriginalMetadataUntouched() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.annotateConnection(id, "a", "主号");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.annotateConnection(id, "a", "x".repeat(41)));
        assertThrows(
                IllegalArgumentException.class, () -> store.annotateConnection(id, "a", "x\ny"));
        assertEquals("主号", store.describe(id).path("connections").path(0).path("note").asText());
        store.annotateConnection(id, "a", "");
        assertEquals("", store.describe(id).path("connections").path(0).path("note").asText());
    }

    @Test
    void messagesBeforeIdentityMustStayUnknownAndSavedNotesRemainEditable() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(
                id,
                player("a", "1", "不可识别")
                        .put("protocolId", 200)
                        .put("protocolName", "GcOtherPlayerInfo"));
        store.annotateConnection(id, "a", "手工标记");
        var unknownSnapshot = store.snapshotCurrent(id, "无登录快照");
        assertEquals("手工标记", unknownSnapshot.path("connections").path(0).path("note").asText());
        store.appendLive(id, player("a", "1", "甲"));
        assertFalse(store.detail(id, 0).has("connectionIdentity"));
        store.stopLive(id, "STOPPED");
        store.persistLive(id, "历史记录");
        var reopened = store();
        reopened.annotateConnection(id, "a", "历史备注");
        assertEquals("历史备注", store().read(id).path("connections").path(0).path("note").asText());
    }

    @Test
    void repeatedLoginMustInvalidateNotesAddedWhileIdentityWasPending() throws Exception {
        var store = store();
        String id = store.createLive("a", "test", "");
        store.appendLive(id, login("a"));
        store.annotateConnection(id, "a", "暂定账号");
        store.appendLive(id, login("a"));
        assertEquals("", store.describe(id).path("connections").path(0).path("note").asText());
        assertEquals(
                2, store.describe(id).path("connections").path(0).path("identityHistory").size());
    }
}
