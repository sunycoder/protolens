package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureAnalysisQuery;
import com.lashouwan.protolens.capture.CaptureMessageQuery;
import com.lashouwan.protolens.capture.CaptureTimeQuery;
import com.lashouwan.protolens.capture.MessageQuery;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 原消息保持不变；投影、折叠、时间定位与摘要只改变只读分析结果。 */
class CaptureAnalysisTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode capture() {
        ObjectNode capture = mapper.createObjectNode().put("id", "synthetic").put("firstIndex", 0);
        capture.putArray("messages");
        capture.putObject("catalog").put("sha256", "version-a");
        return capture;
    }

    private ObjectNode packet(int index, int amount, String time) {
        ObjectNode packet =
                mapper.createObjectNode()
                        .put("index", index)
                        .put("protocolId", 7)
                        .put("protocolName", "GcUpdate")
                        .put("direction", "SC")
                        .put("connection", "a")
                        .put("status", "DECODED")
                        .put("time", time)
                        .put("packetHex", "15");
        packet.putObject("body").put("amount", amount).put("a/b", "184400368043482");
        return packet;
    }

    private MessageQuery query(boolean heartbeat) {
        return new MessageQuery(
                "", "", "", "", heartbeat, 0, Integer.MAX_VALUE, 0, 100, "asc", "", "");
    }

    /** 缺失不伪造零，转义路径读取真实字段，投影不返回完整正文。 */
    @Test
    void projectionMustPreserveMissingAndEscapedFieldSemantics() throws Exception {
        ObjectNode capture = capture();
        capture.withArray("messages").add(packet(0, 0, "10:00:00"));
        var analysis = new CaptureAnalysisQuery(mapper);
        var paths = analysis.fields("[\"/amount\",\"/a~1b\",\"/missing\"]");
        var result =
                new CaptureMessageQuery(mapper)
                        .messages(capture, query(true), mapper.createObjectNode(), paths, false);
        var values = result.path("items").path(0).path("fieldValues");
        assertEquals("0", values.path("/amount").asText());
        assertEquals("184400368043482", values.path("/a~1b").asText());
        assertEquals("—", values.path("/missing").asText());
        assertFalse(result.path("items").path(0).has("body"));
        assertTrue(capture.path("messages").path(0).has("body"));
        assertThrows(IllegalArgumentException.class, () -> analysis.fields("[\"amount\"]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> analysis.fields("[\"/1\",\"/2\",\"/3\",\"/4\",\"/5\",\"/6\",\"/7\"]"));
    }

    /** 只折叠原编号相邻的同消息流完整内容，不能跨连接或用相同摘要代替相同正文。 */
    @Test
    void repeatsMustNeverMergeConnectionsGapsOrDifferentOriginalPackets() {
        ObjectNode first = packet(0, 1, "10:00:00"), second = packet(1, 1, "10:00:01");
        assertTrue(CaptureAnalysisQuery.identical(first, second));
        assertFalse(
                CaptureAnalysisQuery.identical(first, second.deepCopy().put("connection", "b")));
        assertFalse(CaptureAnalysisQuery.identical(first, second.deepCopy().put("index", 2)));
        assertTrue(CaptureAnalysisQuery.identical(first, second.deepCopy().put("packetHex", "16")));
        assertFalse(
                CaptureAnalysisQuery.identical(
                        first.deepCopy().put("status", "PARTIAL"),
                        second.deepCopy().put("status", "PARTIAL").put("packetHex", "16")));
        assertFalse(CaptureAnalysisQuery.identical(first, packet(1, 2, "10:00:01")));
        ObjectNode capture = capture();
        capture.withArray("messages").add(first).add(second).add(packet(2, 2, "10:00:02"));
        var result =
                new CaptureMessageQuery(mapper)
                        .messages(capture, query(true), mapper.createObjectNode(), List.of(), true);
        assertEquals(3, result.path("total").asInt());
        assertEquals(3, result.path("items").size());
        assertEquals(0, result.path("items").path(1).path("repeatOf").asInt(-1));
        assertFalse(result.path("items").path(2).has("repeatOf"));
    }

    /** 操作回顾识别改后恢复，异常按真实状态聚合；分析不得改变原字典或原始数据。 */
    @Test
    void operationReviewMustKeepTransitionsAndRealErrorGroups() {
        ObjectNode capture = capture();
        capture.withArray("messages")
                .add(packet(0, 1, "10:00:00"))
                .add(packet(1, 2, "10:00:01").put("status", "FAILED"))
                .add(packet(2, 1, "10:00:02").put("status", "PARTIAL"));
        String before = capture.toString();
        var result =
                new CaptureAnalysisQuery(mapper).analyze(capture, query(true), List.of("/amount"));
        assertEquals(3, result.path("total").asInt());
        assertEquals(2, result.path("anomalies").size());
        assertEquals(2, result.path("fields").path(0).path("changes").asInt());
        assertFalse(result.path("fields").path(0).path("changed").asBoolean());
        assertEquals(2, result.path("fields").path(0).path("lastIndex").asInt());
        assertFalse(result.path("fields").path(0).path("signature").asText().isEmpty());
        assertEquals(before, capture.toString());
        assertFalse(result.path("timeline").path(0).has("body"));
    }

    /** 时间定位继续隐藏心跳，支持午夜附近窗口和无时间消息，非法输入明确拒绝。 */
    @Test
    void timeLocationMustRespectFiltersAndMidnightWithoutInventingDates() {
        ObjectNode capture = capture();
        capture.withArray("messages")
                .add(packet(0, 1, "23:59:59.900"))
                .add(packet(1, 2, "00:00:00.000").put("protocolName", "GcSynchronizeTime"))
                .add(packet(2, 3, "00:00:00.100"))
                .add(packet(3, 4, "00:00:10.000"));
        var time = new CaptureTimeQuery(mapper);
        var result = time.locate(capture, query(true), "00:00:00.100", 1);
        assertEquals(2, result.path("index").asInt());
        assertEquals(0, result.path("from").asInt());
        assertEquals(2, result.path("to").asInt());
        assertEquals(1, time.locate(capture, query(false), "00:00:00", 0).path("index").asInt());
        assertThrows(
                IllegalArgumentException.class,
                () -> time.locate(capture, query(true), "25:00:00", 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> time.locate(capture, query(true), "10:00:00", 61));
    }

    /** 时间线达到展示预算时告知截断，数量统计仍覆盖已扫描范围。 */
    @Test
    void largeReviewMustDeclareTimelineAndRetentionBoundaries() {
        ObjectNode capture = capture().put("firstIndex", 5);
        for (int index = 5; index < 2010; index++) {
            capture.withArray("messages").add(packet(index, index, "10:00:00"));
        }
        var result = new CaptureAnalysisQuery(mapper).analyze(capture, query(true), List.of());
        assertEquals(2005, result.path("total").asInt());
        assertEquals(2000, result.path("timeline").size());
        assertTrue(result.path("timelineTruncated").asBoolean());
        assertTrue(result.path("missingBefore").asBoolean());
    }
}
