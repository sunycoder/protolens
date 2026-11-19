package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.MessageQuery;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

/** 合成记录验证固定详情所需的统计、后续消息和筛选证据，不启动游戏、采集器或系统代理。 */
class CaptureReadingTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private CaptureStore store() throws Exception {
        return new CaptureStore(
                directory,
                mapper,
                new ProtocolCatalog(
                        mapper.readTree(
                                """
{"schemaVersion":1,"messages":{"0":{"name":"CgZero"},"7":{"name":"GcUpdate"}},"definitions":{"0":{},"7":{}}}
""")));
    }

    private ObjectNode packet(int protocol, String connection, String direction, String status) {
        ObjectNode item =
                mapper.createObjectNode()
                        .put("protocolId", protocol)
                        .put("protocolName", protocol == 7 ? "GcUpdate" : "CgZero")
                        .put("connection", connection)
                        .put("direction", direction)
                        .put("status", status)
                        .put("bytes", 20)
                        .put("time", "10:00:00");
        item.putObject("body").put("value", 1);
        return item;
    }

    private MessageQuery query(String protocol, String connection, String exclude, String status) {
        return new MessageQuery(
                "",
                "",
                status,
                connection,
                false,
                0,
                Integer.MAX_VALUE,
                0,
                100,
                "desc",
                "",
                exclude,
                protocol,
                "");
    }

    private JsonNode group(JsonNode activity, String token) {
        for (JsonNode item : activity.path("groups")) {
            if (token.equals(item.path("token").asText())) return item;
        }
        throw new AssertionError(token);
    }

    /** 累计活动跨淘汰与清空保留首次编号，旧快照不可被后续追加修改。 */
    @Test
    void activityMustKeepLifetimeCountsAndFirstSeenAcrossEvictionAndClear() throws Exception {
        var store = store();
        store.configureLive(100, 1024 * 1024);
        String id = store.createLive("a", "synthetic", "");
        store.appendLive(id, packet(0, "a", "CS", "DECODED"));
        var first = store.activity(id);
        for (int index = 0; index < 105; index++)
            store.appendLive(id, packet(7, "a", "SC", index == 0 ? "PARTIAL" : "DECODED"));
        var latest = store.activity(id);
        assertEquals(106, latest.path("total").asInt());
        assertEquals(1, latest.path("attention").asInt());
        assertEquals(0, group(latest, "id:0").path("firstIndex").asInt(-1));
        assertEquals(1, group(latest, "id:7").path("firstIndex").asInt(-1));
        assertEquals(1, first.path("total").asInt());
        assertEquals(6, latest.path("firstRetained").asInt());
        store.clearTemporary(Set.of(id));
        store.appendLive(id, packet(0, "a", "CS", "UNMAPPED"));
        var cleared = store.activity(id);
        assertEquals(1, cleared.path("clearVersion").asInt());
        assertEquals(107, cleared.path("total").asInt());
        assertEquals(0, group(cleared, "id:0").path("firstIndex").asInt(-1));
        assertEquals(2, cleared.path("attention").asInt());
    }

    /** 页面快照与列表同步冻结，协议筛选不缩小统计范围；MCP 的既有查询不附带大统计。 */
    @Test
    void listActivityMustBeUnfilteredAndOptIn() throws Exception {
        var store = store();
        String id = store.createLive("a", "synthetic", "");
        store.appendLive(id, packet(0, "a", "CS", "DECODED"));
        store.appendLive(id, packet(7, "a", "SC", "FAILED"));
        var request = query("0", "", "", "");
        var page = store.messages(id, request, true);
        assertEquals(1, page.path("total").asInt());
        assertEquals(2, page.path("activity").path("total").asInt());
        assertEquals(1, page.path("activity").path("lastIndex").asInt());
        assertFalse(store.messages(id, request).has("activity"));
    }

    /** 通知窗口必须与列表的筛选结果一致；只有隐藏心跳时不能出现可查看通知。 */
    @Test
    void activityWindowMustCountOnlyVisibleMessagesUsingTheListMatcher() throws Exception {
        var store = store();
        String id = store.createLive("a", "synthetic", "");
        store.appendLive(
                id, packet(0, "a", "SC", "DECODED").put("protocolName", "GcSynchronizeTime"));
        var heartbeatOnly = new MessageQuery("", "", "", "", true, 0, 0, 0, 1, "desc", "", "");
        assertEquals(0, store.activityWindow(id, heartbeatOnly).path("total").asInt());
        store.appendLive(id, packet(7, "a", "SC", "DECODED"));
        store.appendLive(id, packet(7, "b", "SC", "DECODED"));
        store.appendLive(id, packet(7, "a", "CS", "DECODED"));
        store.appendLive(id, packet(7, "a", "SC", "FAILED"));
        ObjectNode otherValue = packet(7, "a", "SC", "DECODED");
        ((ObjectNode) otherValue.path("body")).put("value", 2);
        store.appendLive(id, otherValue);
        var request =
                new MessageQuery(
                        "1", "SC", "DECODED", "a", true, 0, 5, 0, 1, "desc", "id:7", "id:0");
        var window = store.activityWindow(id, request);
        assertEquals(
                store.messages(id, request).path("total").asLong(), window.path("total").asLong());
        assertEquals(1, window.path("total").asInt());
        assertEquals(0, window.path("attention").asInt());
        assertEquals(1, window.path("groups").size());
        assertEquals(1, group(window, "id:7").path("firstIndex").asInt());
        assertTrue(window.path("complete").asBoolean());
        assertFalse(window.toString().contains("\"body\""));
        assertEquals(6, store.activity(id).path("total").asInt());
    }

    /** 通知不把已淘汰消息算作仍可查看，且明确保留范围之外的缺失。 */
    @Test
    void activityWindowMustExcludeEvictedMessagesAndDeclareTheMissingRange() throws Exception {
        var store = store();
        store.configureLive(100, 1024 * 1024);
        String id = store.createLive("a", "synthetic", "");
        for (int index = 0; index < 105; index++) {
            store.appendLive(id, packet(7, "a", "SC", "DECODED"));
        }
        var window = store.activityWindow(id, query("", "", "", ""));
        assertEquals(100, window.path("total").asInt());
        assertEquals(5, window.path("firstRetained").asInt());
        assertFalse(window.path("complete").asBoolean());
    }

    /** 后续消息必须同时匹配连接、方向及协议 ID，摘要不含消息体。 */
    @Test
    void laterMessagesMustNeverMixOtherConnectionsDirectionsOrProtocols() throws Exception {
        var store = store();
        String id = store.createLive("a", "synthetic", "");
        store.appendLive(id, packet(0, "a", "CS", "DECODED"));
        store.appendLive(id, packet(0, "b", "CS", "DECODED"));
        store.appendLive(id, packet(0, "a", "SC", "DECODED"));
        store.appendLive(id, packet(7, "a", "CS", "DECODED"));
        store.appendLive(id, packet(0, "a", "CS", "DECODED"));
        var result = store.latestSameProtocol(id, "0", "CgZero", "a", "CS", 0);
        assertEquals(1, result.path("count").asInt());
        assertEquals(4, result.path("latest").path("index").asInt());
        assertFalse(result.path("latest").has("body"));
        assertFalse(result.path("truncated").asBoolean());
    }

    /** 原详情已淘汰仍能查保留窗口的后续包，并明确数量只是下界；不依赖旧包存在。 */
    @Test
    void retainedLaterCountMustDeclareMissingRange() throws Exception {
        var store = store();
        store.configureLive(100, 1024 * 1024);
        String id = store.createLive("a", "synthetic", "");
        for (int index = 0; index < 103; index++)
            store.appendLive(id, packet(7, "a", "SC", "DECODED"));
        assertThrows(IllegalArgumentException.class, () -> store.detail(id, 0));
        var result = store.latestSameProtocol(id, "7", "GcUpdate", "a", "SC", 0);
        assertTrue(result.path("truncated").asBoolean());
        assertEquals(100, result.path("count").asInt());
        assertEquals(102, result.path("latest").path("index").asInt());
    }

    /** 只有解除一项便能命中真实消息时才给建议；同时冲突两项时不伪造单项原因。 */
    @Test
    void emptyResultsMustProvideSingleFilterEvidenceAndExactExcludedTokens() throws Exception {
        var store = store();
        String id = store.createLive("a", "synthetic", "");
        store.appendLive(id, packet(7, "a", "SC", "DECODED"));
        var hidden = store.messages(id, query("7", "a", "id:7,id:70", ""));
        assertEquals(0, hidden.path("total").asInt());
        assertEquals("exclude", hidden.path("emptyReasons").path(0).path("field").asText());
        assertEquals("id:7", hidden.path("emptyReasons").path(0).path("value").asText());
        assertTrue(store.messages(id, query("7", "b", "id:7", "")).path("emptyReasons").isEmpty());
        assertEquals(1, store.messages(id, query("7", "a", "id:70", "")).path("total").asInt());
    }

    /** “需关注”是筛选集合，不改变消息自身的解码状态，也不把部分解码伪装成失败。 */
    @Test
    void attentionFilterMustIncludePartialFailedAndUnmappedWithoutChangingStatus()
            throws Exception {
        var store = store();
        String id = store.createLive("a", "synthetic", "");
        for (String status : new String[] {"DECODED", "PARTIAL", "FAILED", "UNMAPPED"})
            store.appendLive(id, packet(7, "a", "SC", status));
        var result = store.messages(id, query("7", "", "", "ATTENTION"));
        assertEquals(3, result.path("total").asInt());
        assertEquals("UNMAPPED", result.path("items").path(0).path("status").asText());
        assertEquals("PARTIAL", result.path("items").path(2).path("status").asText());
    }
}
