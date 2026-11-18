package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.enums.MessageOrder;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对仓库持锁提供的记录执行过滤、排序和摘要分页；不做文件 IO，不修改记录。 */
public final class CaptureMessageQuery {
    private static final Set<String> OMITTED_FIELDS =
            Set.of("body", "packetHex", "wireHex", "schema", "definition");

    /** 搜索摘要仅处理当前页消息；限制遍历节点、展示字段数和路径长度，避免大包拖慢翻页。 */
    private static final int SEARCH_PREVIEW_MAX_NODES = 10_000;

    private static final int SEARCH_PREVIEW_MAX_FIELDS = 4;
    private static final int SEARCH_PREVIEW_MAX_PATH_LENGTH = 1024;
    private final ObjectMapper mapper;

    public CaptureMessageQuery(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode messages(ObjectNode capture, MessageQuery request, ObjectNode summary) {
        String query = request.query();
        String direction = request.direction();
        String status = request.status();
        String connection = request.connection();
        boolean hideHeartbeat = request.hideHeartbeat();
        int from = request.from();
        int to = request.to();
        int offset = request.offset();
        int limit = request.limit();
        MessageOrder order = request.order();
        String include = request.include();
        String exclude = request.exclude();
        String protocolId = request.protocolId();
        String messageIndex = request.messageIndex();

        List<JsonNode> matching = new ArrayList<>();
        String keyword = query.toLowerCase(Locale.ROOT);
        for (JsonNode node : capture.path("messages")) {
            int index = node.path("index").asInt();
            if (!messageIndex.isBlank() && !messageIndex.equals(Integer.toString(index))) {
                continue;
            }
            if (index < from
                    || index > to
                    || (!direction.isBlank() && !direction.equals(node.path("direction").asText()))
                    || (!status.isBlank() && !status.equals(node.path("status").asText()))
                    || (!connection.isBlank()
                            && !connection.equals(node.path("connection").asText()))) {
                continue;
            }
            String protocol =
                    node.path("protocolName").asText() + " " + node.path("protocolId").asText();
            if (!protocolId.isBlank() && !protocolId.equals(node.path("protocolId").asText())) {
                continue;
            }
            if (hideHeartbeat && protocol.contains("SynchronizeTime")) {
                continue;
            }
            if (!include.isBlank() && !matchesProtocol(protocol, include)) {
                continue;
            }
            if (!exclude.isBlank() && matchesProtocol(protocol, exclude)) {
                continue;
            }
            if (!keyword.isBlank()) {
                String searchable = protocol + " " + node.path("body");
                if (!searchable.toLowerCase(Locale.ROOT).contains(keyword)) {
                    continue;
                }
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
            for (int fieldIndex = 0; body.hasNext() && fieldIndex < 4; fieldIndex++) {
                Map.Entry<String, JsonNode> field = body.next();
                if (!preview.isEmpty()) {
                    preview.append(" · ");
                }
                JsonNode value = field.getValue();
                String text = value.isContainerNode() ? "[" + value.size() + "项]" : value.asText();
                preview.append(field.getKey())
                        .append('=')
                        .append(text.substring(0, Math.min(60, text.length())));
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
                int start = Math.max(0, lowerText.indexOf(keyword) - 20);
                matches.add(
                        path
                                + "="
                                + (start > 0 ? "…" : "")
                                + text.substring(start, Math.min(text.length(), start + 80)));
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

    private boolean matchesProtocol(String protocol, String patterns) {
        String value = protocol.toLowerCase(Locale.ROOT);
        for (String pattern : patterns.split("[,，\\s]+")) {
            if (!pattern.isBlank() && value.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
