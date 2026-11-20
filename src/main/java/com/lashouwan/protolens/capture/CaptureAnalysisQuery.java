package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留消息的只读分析：字段投影、操作摘要与异常归组，不修改消息或推断协议关联。 */
public final class CaptureAnalysisQuery {
    private static final int MAX_FIELDS = 6;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_VALUE_LENGTH = 160;
    private static final int MAX_SCAN_MESSAGES = 200_000;
    private static final long MAX_SCAN_NANOS = 250_000_000L;
    private static final int MAX_GROUPS = 1000;
    private static final int MAX_TIMELINE = 2000;
    private final ObjectMapper mapper;

    public CaptureAnalysisQuery(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 字段路径采用 JSON Pointer，限制数量与长度；不允许任意脚本或表达式。 */
    public List<String> fields(String input) throws IOException {
        if (input.length() > 4096) {
            throw new IllegalArgumentException("字段列参数过长");
        }
        JsonNode values;
        try {
            values = mapper.readTree(input);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("字段列必须是 JSON 字符串数组", failure);
        }
        if (values == null || !values.isArray() || values.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("最多选择 6 个字段列");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String path = value.asText();
            if (!value.isTextual()
                    || path.isEmpty()
                    || path.length() > MAX_PATH_LENGTH
                    || !path.startsWith("/")
                    || path.matches("(?s).*~(?![01]).*")) {
                throw new IllegalArgumentException("字段路径必须是有效的 JSON Pointer，长度不超过 512 字符");
            }
            JsonPointer.compile(path);
            if (!result.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    /** 只在当前页按需投影；容器展示条目数、缺失展示破折号，不下发完整大对象。 */
    public static void project(JsonNode message, ObjectNode item, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        ObjectNode values = item.putObject("fieldValues");
        for (String path : paths) {
            values.put(path, display(message.path("body").at(path)));
        }
    }

    /** 原编号相邻且属于同消息流；完整解码比较业务正文，非完整解码额外核对原始包，避免隐藏未知差异。 */
    public static boolean identical(JsonNode before, JsonNode after) {
        if (before == null
                || Math.abs((long) before.path("index").asInt() - after.path("index").asInt())
                        != 1) {
            return false;
        }
        for (String field :
                List.of(
                        "connection",
                        "direction",
                        "protocolId",
                        "protocolName",
                        "status",
                        "body",
                        "error",
                        "warnings")) {
            if (!before.path(field).equals(after.path(field))) {
                return false;
            }
        }
        if ("DECODED".equals(before.path("status").asText()) && before.hasNonNull("body")) {
            return true;
        }
        for (String field : List.of("packetHex", "wireHex")) {
            if (!before.path(field).equals(after.path(field))) {
                return false;
            }
        }
        // 未解码且没有原始内容时，不能用两个空对象证明消息相同。
        return before.hasNonNull("body")
                || !before.path("packetHex").asText().isEmpty()
                || !before.path("wireHex").asText().isEmpty();
    }

    public static String display(JsonNode value) {
        if (value.isMissingNode()) {
            return "—";
        }
        if (value.isContainerNode()) {
            return (value.isArray() ? "[" : "{")
                    + value.size()
                    + " 项"
                    + (value.isArray() ? "]" : "}");
        }
        String text = value.isNull() ? "null" : value.asText();
        return text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) + "…" : text;
    }

    /**
     * 用户主动打开操作回顾、对照或异常面板时扫描稳定视图，沿用列表筛选。 数量、样本与时间线均有上限；缺失、淘汰与截断显式返回，不伪装为完整分析。
     * 字段变化只表示区间内首次和末次实际出现的值，不宣称服务器状态或因果关系。
     */
    public ObjectNode analyze(ObjectNode capture, MessageQuery query, List<String> paths) {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("captureId", capture.path("id").asText())
                        .put("from", query.from())
                        .put("to", query.to())
                        .put("total", 0)
                        .put("missingBefore", query.from() < capture.path("firstIndex").asInt())
                        .put("dictionary", capture.path("catalog").path("sha256").asText());
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        Map<String, ObjectNode> anomalies = new LinkedHashMap<>();
        Map<String, JsonNode> firstValues = new LinkedHashMap<>();
        Map<String, JsonNode> lastValues = new LinkedHashMap<>();
        Map<String, Integer> lastIndices = new LinkedHashMap<>();
        Map<String, Integer> changeCounts = new LinkedHashMap<>();
        var timeline = result.putArray("timeline");
        int visited = 0;
        int total = 0;
        boolean truncated = false;
        long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        for (JsonNode message : capture.path("messages")) {
            int index = message.path("index").asInt();
            if (index < query.from()) {
                continue;
            }
            if (index > query.to()) {
                break;
            }
            if (++visited > MAX_SCAN_MESSAGES || System.nanoTime() > deadline) {
                truncated = true;
                break;
            }
            if (CaptureMessageMatcher.metadataFailures(message, query) != 0
                    || !CaptureMessageMatcher.keywordMatches(message, query.query())) {
                continue;
            }
            total++;
            String key =
                    message.path("protocolId").asText()
                            + ":"
                            + message.path("protocolName").asText()
                            + ":"
                            + message.path("direction").asText();
            truncated |= !count(groups, key, message);
            if (CaptureActivityTracker.attention(message.path("status").asText())) {
                truncated |=
                        !count(anomalies, key + ":" + message.path("status").asText(), message);
            }
            if (timeline.size() < MAX_TIMELINE) {
                ObjectNode sample = timeline.addObject();
                for (String field :
                        List.of(
                                "index",
                                "protocolId",
                                "protocolName",
                                "direction",
                                "connection",
                                "time",
                                "status")) {
                    sample.set(field, message.path(field));
                }
            }
            for (String path : paths) {
                JsonNode value = message.path("body").at(path);
                if (!value.isMissingNode()) {
                    firstValues.putIfAbsent(path, value);
                    JsonNode previous = lastValues.put(path, value);
                    if (previous != null && !previous.equals(value)) {
                        changeCounts.merge(path, 1, Integer::sum);
                    }
                    lastIndices.put(path, index);
                }
            }
        }
        result.put("total", total)
                .put("truncated", truncated)
                .put("timelineTruncated", total > MAX_TIMELINE);
        var groupArray = result.putArray("groups");
        groups.values().forEach(groupArray::add);
        var anomalyArray = result.putArray("anomalies");
        anomalies.values().stream()
                .sorted(
                        Comparator.comparingInt((ObjectNode node) -> node.path("count").asInt())
                                .reversed())
                .forEach(anomalyArray::add);
        var changes = result.putArray("fields");
        for (String path : paths) {
            JsonNode first = firstValues.get(path), last = lastValues.get(path);
            changes.addObject()
                    .put("path", path)
                    .put("first", first == null ? "—" : display(first))
                    .put("last", last == null ? "—" : display(last))
                    .put("changed", first != null && !first.equals(last))
                    .put("changes", changeCounts.getOrDefault(path, 0))
                    .put("lastIndex", lastIndices.getOrDefault(path, -1))
                    .put("signature", last == null ? "" : signature(last));
        }
        return result;
    }

    private boolean count(Map<String, ObjectNode> groups, String key, JsonNode message) {
        ObjectNode group = groups.get(key);
        if (group == null) {
            if (groups.size() >= MAX_GROUPS) {
                return false;
            }
            group =
                    mapper.createObjectNode()
                            .put("key", key)
                            .put("count", 0)
                            .put("firstIndex", message.path("index").asInt());
            for (String field :
                    List.of("protocolId", "protocolName", "direction", "status", "time")) {
                group.set(field, message.path(field));
            }
            groups.put(key, group);
        }
        group.put("count", group.path("count").asInt() + 1)
                .put("lastIndex", message.path("index").asInt());
        return true;
    }

    /** 标量签名仅用于判断实际值是否改变；超大值与容器不提供签名，避免截断显示导致误判。 */
    private String signature(JsonNode value) {
        if (!value.isValueNode() || value.asText().length() > 4096) {
            return "";
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }
}
