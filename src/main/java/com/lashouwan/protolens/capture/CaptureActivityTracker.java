package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.enums.DecodeStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单份记录的轻量累计统计，不保存消息体。实时缓存淘汰和清空不会把已经见过的协议重新标成首次。 所有更新由 LiveMessageBuffer
 * 所属仓库锁保护；历史记录仅从保留消息重建，并明确完整性边界。
 */
final class CaptureActivityTracker {
    private static final int MAX_PROTOCOL_GROUPS = 8192;
    private final Map<String, ObjectNode> groups = new LinkedHashMap<>();
    private long total;
    private long attention;
    private boolean complete = true;
    private int lastIndex = -1;

    void append(JsonNode message, ObjectMapper mapper) {
        total++;
        int index = message.path("index").asInt();
        lastIndex = Math.max(lastIndex, index);
        boolean needsAttention = attention(message.path("status").asText());
        if (needsAttention) {
            attention++;
        }
        String token =
                message.hasNonNull("protocolId")
                        ? "id:" + message.path("protocolId").asText()
                        : message.path("protocolName").asText().isBlank()
                                ? ""
                                : "name:" + message.path("protocolName").asText();
        ObjectNode group = groups.get(token);
        if (group == null) {
            if (groups.size() >= MAX_PROTOCOL_GROUPS) {
                complete = false;
                return;
            }
            group =
                    mapper.createObjectNode()
                            .put("token", token)
                            .put("name", message.path("protocolName").asText("未识别协议"))
                            .put("count", 0L)
                            .put("attentionCount", 0L)
                            .put("firstIndex", index);
            if (message.hasNonNull("protocolId")) {
                group.set("protocolId", message.get("protocolId").deepCopy());
            }
            groups.put(token, group);
        }
        group.put("count", group.path("count").asLong() + 1).put("lastIndex", index);
        if (needsAttention) {
            group.put("attentionCount", group.path("attentionCount").asLong() + 1)
                    .put("lastAttentionIndex", index);
        }
    }

    ObjectNode snapshot(ObjectMapper mapper, long clearVersion, int firstRetained) {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("total", total)
                        .put("attention", attention)
                        .put("mode", "lifetime")
                        .put("lastIndex", lastIndex)
                        .put("clearVersion", clearVersion)
                        .put("firstRetained", firstRetained)
                        .put("complete", complete);
        var items = result.putArray("groups");
        groups.values().forEach(group -> items.add(group.deepCopy()));
        return result;
    }

    /** 从旧记录重建时只有原编号从 0 连续且消息未丢失，才可宣称掌握本记录首次出现。 */
    static ObjectNode retainedSnapshot(JsonNode capture, ObjectMapper mapper) {
        var tracker = new CaptureActivityTracker();
        int expected = 0;
        for (JsonNode message : capture.path("messages")) {
            if (message.path("index").asInt(-1) != expected) {
                tracker.complete = false;
            }
            expected = message.path("index").asInt() + 1;
            tracker.append(message, mapper);
        }
        tracker.complete &=
                capture.path("clearedCount").asLong() == 0
                        && (!capture.has("totalReceived")
                                || capture.path("totalReceived").asLong() == tracker.total);
        ObjectNode result =
                tracker.snapshot(
                        mapper,
                        capture.path("clearVersion").asLong(),
                        capture.path("firstIndex").asInt());
        return result.put("mode", "retained").put("scope", "保留范围统计；历史缺失时不标记本记录首次出现");
    }

    static boolean attention(String status) {
        return DecodeStatus.PARTIAL.name().equals(status)
                || DecodeStatus.FAILED.name().equals(status)
                || DecodeStatus.UNMAPPED.name().equals(status);
    }
}
