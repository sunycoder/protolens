package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.enums.MessageOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对仓库持锁提供的记录执行过滤、排序和摘要分页；不做文件 IO，不修改记录。 */
public final class CaptureMessageQuery {
    private static final Set<String> OMITTED_FIELDS =
            Set.of("body", "packetHex", "wireHex", "schema", "definition");
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

        List<JsonNode> matching = new ArrayList<>();
        String keyword = query.toLowerCase(Locale.ROOT);
        for (JsonNode node : capture.path("messages")) {
            int index = node.path("index").asInt();
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
            item.put("preview", preview.toString());
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
