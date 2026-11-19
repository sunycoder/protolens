package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.enums.MessageFilterCondition;
import com.lashouwan.protolens.enums.MessageOrder;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对仓库发布的稳定消息视图执行过滤、排序和摘要分页；锁外计算，不修改消息。 */
public final class CaptureMessageQuery {
    private static final Set<String> OMITTED_FIELDS =
            Set.of("body", "packetHex", "wireHex", "schema", "definition");

    /** 搜索摘要仅处理当前页消息；限制遍历节点、展示字段数和路径长度，避免大包拖慢翻页。 */
    private static final int SEARCH_PREVIEW_MAX_NODES = 10_000;

    private static final int SEARCH_PREVIEW_MAX_FIELDS = 4;
    private static final int SEARCH_PREVIEW_MAX_PATH_LENGTH = 1024;
    private static final int PREVIEW_MAX_FIELDS = 4;
    private static final int PREVIEW_VALUE_LENGTH = 60;
    private static final int SEARCH_PREVIEW_CONTEXT_BEFORE = 20;
    private static final int SEARCH_PREVIEW_VALUE_LENGTH = 80;
    private static final int MAX_DIAGNOSTIC_MESSAGES = 5000;
    private static final long DIAGNOSTIC_NANOS = 50_000_000L;
    private static final int MAX_DIAGNOSTIC_BODY_BYTES = 128 * 1024;
    private static final int MAX_DIAGNOSTIC_REASONS = 3;
    private final ObjectMapper mapper;

    public CaptureMessageQuery(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 通知只统计指定原消息区间中当前筛选实际可见的消息，与列表共用同一匹配规则。 锁外读取稳定视图，不复制消息体、不生成列表摘要，也不把已淘汰的消息计为可查看。
     * 返回的首次编号仅为区间内首次；是否属于本记录首次由调用方与累计统计核对。
     */
    public ObjectNode activityWindow(ObjectNode capture, MessageQuery request) {
        var tracker = new CaptureActivityTracker();
        JsonNode messages = capture.path("messages");
        int lower = lowerBound(messages, request.from());
        int upper =
                lowerBound(
                        messages,
                        request.to() == Integer.MAX_VALUE ? request.to() : request.to() + 1);
        for (int index = lower; index < upper; index++) {
            JsonNode message = messages.path(index);
            if (CaptureMessageMatcher.metadataFailures(message, request) == 0
                    && CaptureMessageMatcher.keywordMatches(message, request.query())) {
                tracker.append(message, mapper);
            }
        }
        ObjectNode result =
                tracker.snapshot(
                        mapper,
                        capture.path("clearVersion").asLong(),
                        capture.path("firstIndex").asInt());
        return result.put("mode", "retained")
                .put(
                        "complete",
                        result.path("complete").asBoolean()
                                && request.from() >= capture.path("firstIndex").asInt());
    }

    public ObjectNode messages(ObjectNode capture, MessageQuery request, ObjectNode summary) {
        String query = request.query();
        int from = request.from();
        int to = request.to();
        int offset = request.offset();
        int limit = request.limit();
        MessageOrder order = request.order();
        String messageIndex = request.messageIndex();

        List<JsonNode> matching = new ArrayList<>();
        String keyword = query.toLowerCase(Locale.ROOT);
        JsonNode messages = capture.path("messages");
        // 原编号递增但可能有淘汰或间隙；二分定位序号下界，避免序号查询扫描整份消息体。
        int lower = lowerBound(messages, from);
        int upper = lowerBound(messages, to == Integer.MAX_VALUE ? to : to + 1);
        if (!messageIndex.isBlank()) {
            lower = lowerBound(messages, Integer.parseInt(messageIndex));
            upper = Math.min(messages.size(), lower + 1);
        }
        for (int candidate = lower; candidate < upper; candidate++) {
            JsonNode node = messages.path(candidate);
            if (CaptureMessageMatcher.metadataFailures(node, request) != 0
                    || !CaptureMessageMatcher.keywordMatches(node, query)) {
                continue;
            }
            matching.add(node);
        }
        if (order == MessageOrder.DESC) {
            Collections.reverse(matching);
        }
        int start =
                offset < 0
                        ? (order == MessageOrder.DESC ? 0 : Math.max(0, matching.size() - limit))
                        : Math.min(offset, matching.size());
        ArrayNode items = mapper.createArrayNode();
        for (int index = start; index < Math.min(start + limit, matching.size()); index++) {
            JsonNode node = matching.get(index);
            ObjectNode item = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!OMITTED_FIELDS.contains(field.getKey())) {
                    item.set(field.getKey(), field.getValue().deepCopy());
                }
            }
            // 只摘要顶层前四个字段，大容器显示条数，不下发完整 body。
            StringBuilder preview = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> body = node.path("body").fields();
            for (int fieldIndex = 0;
                    body.hasNext() && fieldIndex < PREVIEW_MAX_FIELDS;
                    fieldIndex++) {
                Map.Entry<String, JsonNode> field = body.next();
                if (!preview.isEmpty()) {
                    preview.append(" · ");
                }
                JsonNode value = field.getValue();
                String text = value.isContainerNode() ? "[" + value.size() + "项]" : value.asText();
                preview.append(field.getKey())
                        .append('=')
                        .append(text, 0, Math.min(PREVIEW_VALUE_LENGTH, text.length()));
            }
            // 搜索时优先展示实际命中的嵌套字段；协议名匹配但消息体未命中时保留普通摘要。
            String matchedPreview =
                    keyword.isBlank() ? "" : searchPreview(node.path("body"), keyword);
            item.put("preview", matchedPreview.isBlank() ? preview.toString() : matchedPreview);
            items.add(item);
        }
        ObjectNode result =
                mapper.createObjectNode()
                        .put("total", matching.size())
                        .put("offset", start)
                        .put("limit", limit);
        result.set("items", items);
        result.set("capture", summary);
        if (matching.isEmpty()) {
            diagnose(messages, request, result);
        }
        return result;
    }

    /**
     * 为单条分页结果生成命中字段摘要，不修改消息体，也不下发完整消息。
     *
     * <p>使用有界广度遍历查找字段路径或标量值。最多检查一万个节点、展示四个字段； 达到预算时停止，仅用于展示摘要，不能据此否定原搜索结果。路径采用 JSON Pointer 转义。
     *
     * @param body 当前消息体
     * @param keyword 已转小写的非空查询词
     * @return 命中路径和值；预算内未找到时返回空字符串，由调用方保留默认摘要
     */
    private String searchPreview(JsonNode body, String keyword) {
        Deque<Map.Entry<String, JsonNode>> pending = new ArrayDeque<>();
        pending.add(new AbstractMap.SimpleImmutableEntry<>("", body));
        List<String> matches = new ArrayList<>();
        int visited = 0;
        while (!pending.isEmpty()
                && visited < SEARCH_PREVIEW_MAX_NODES
                && matches.size() < SEARCH_PREVIEW_MAX_FIELDS) {
            Map.Entry<String, JsonNode> field = pending.removeFirst();
            visited++;
            String path = field.getKey();
            JsonNode value = field.getValue();
            String text = value.isContainerNode() ? "[" + value.size() + "项]" : value.asText();
            String lowerText = text.toLowerCase(Locale.ROOT);
            if (!path.isEmpty()
                    && (path.toLowerCase(Locale.ROOT).contains(keyword)
                            || (!value.isContainerNode() && lowerText.contains(keyword)))) {
                // 长文本保留命中附近内容，而非始终裁剪开头导致搜索词不可见。
                int start = Math.max(0, lowerText.indexOf(keyword) - SEARCH_PREVIEW_CONTEXT_BEFORE);
                matches.add(
                        path
                                + "="
                                + (start > 0 ? "…" : "")
                                + text.substring(
                                        start,
                                        Math.min(
                                                text.length(),
                                                start + SEARCH_PREVIEW_VALUE_LENGTH)));
            }
            if (!value.isContainerNode() || path.length() >= SEARCH_PREVIEW_MAX_PATH_LENGTH) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            int arrayIndex = 0;
            Iterator<JsonNode> elements = value.elements();
            while (value.isArray() ? elements.hasNext() : fields.hasNext()) {
                // 入队也受同一节点预算约束，不因超大对象或数组先分配无界待处理队列。
                if (visited + pending.size() >= SEARCH_PREVIEW_MAX_NODES) {
                    break;
                }
                String key;
                JsonNode child;
                if (value.isArray()) {
                    key = Integer.toString(arrayIndex++);
                    child = elements.next();
                } else {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    key = entry.getKey();
                    child = entry.getValue();
                }
                String childPath = path + "/" + key.replace("~", "~0").replace("/", "~1");
                pending.addLast(new AbstractMap.SimpleImmutableEntry<>(childPath, child));
            }
        }
        return String.join(" · ", matches);
    }

    /**
     * 只在无结果时寻找单项阻碍的证据，不猜测“协议不存在”或静默清空全部筛选。 最多扫描 5000 条和 50ms；带正文查询时跳过未知大小或超过 128KiB 的包，并明确诊断不完整。
     */
    private void diagnose(JsonNode messages, MessageQuery query, ObjectNode result) {
        var reasons = result.putArray("emptyReasons");
        Set<MessageFilterCondition> found = EnumSet.noneOf(MessageFilterCondition.class);
        long deadline = System.nanoTime() + DIAGNOSTIC_NANOS;
        boolean truncated = false;
        int scanned = 0;
        for (JsonNode node : messages) {
            if (scanned++ >= MAX_DIAGNOSTIC_MESSAGES || System.nanoTime() > deadline) {
                truncated = true;
                break;
            }
            int failures = CaptureMessageMatcher.metadataFailures(node, query);
            if (Integer.bitCount(failures) != 1) {
                continue;
            }
            if (!query.query().isBlank()
                    && (!node.has("bytes")
                            || node.path("bytes").asLong() > MAX_DIAGNOSTIC_BODY_BYTES)) {
                truncated = true;
                continue;
            }
            if (!CaptureMessageMatcher.keywordMatches(node, query.query())) {
                continue;
            }
            for (var condition : MessageFilterCondition.values()) {
                if (condition.mask() != failures || !found.add(condition)) {
                    continue;
                }
                reasons.addObject()
                        .put("field", condition.field())
                        .put("description", condition.description())
                        .put("action", condition.action())
                        .put("witnessIndex", node.path("index").asInt())
                        .put(
                                "value",
                                condition == MessageFilterCondition.EXCLUDE
                                        ? CaptureMessageMatcher.excludedTokens(
                                                node, query.exclude())
                                        : "");
            }
            if (reasons.size() >= MAX_DIAGNOSTIC_REASONS) {
                break;
            }
        }
        result.put("diagnosticTruncated", truncated);
    }

    private int lowerBound(JsonNode messages, int index) {
        int low = 0;
        int high = messages.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (messages.path(middle).path("index").asInt() < index) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
