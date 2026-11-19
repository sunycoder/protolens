package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** 静态协议字典快照；竞品代码只由离线提取器读取，服务不执行任何客户端脚本。 */
public final class ProtocolCatalog {
    private volatile JsonNode document;
    private volatile ProtocolQueryIndex queryIndex;

    public ProtocolCatalog(JsonNode document) {
        if (document.path("schemaVersion").asInt() != 1
                || !document.path("messages").isObject()
                || !document.path("definitions").isObject()) {
            throw new IllegalArgumentException("协议字典格式无效，请重新提取");
        }
        this.document = document.deepCopy();
    }

    public void replace(JsonNode replacement) {
        document = new ProtocolCatalog(replacement).snapshot();
    }

    /** 仅首次查询构建索引。索引绑定读取时的同一份文档，换版不影响已经开始的查询。 */
    public synchronized ProtocolQueryIndex queryIndex(ObjectMapper mapper) {
        JsonNode current = document;
        if (queryIndex == null || !queryIndex.belongsTo(current)) {
            queryIndex = new ProtocolQueryIndex(current, mapper);
        }
        return queryIndex;
    }

    /** 字典不存在时保留导入能力，但消息明确标为未映射，不猜测名称和字段。 */
    public static ProtocolCatalog load(Path path, ObjectMapper mapper) throws IOException {
        if (!Files.exists(path)) {
            ObjectNode empty = mapper.createObjectNode().put("schemaVersion", 1);
            empty.putObject("messages");
            empty.putObject("definitions");
            return new ProtocolCatalog(empty);
        }
        return new ProtocolCatalog(mapper.readTree(path.toFile()));
    }

    public String name(int id) {
        return document.path("messages")
                .path(Integer.toString(id))
                .path("name")
                .asText("UNKNOWN_" + id);
    }

    public JsonNode fields(String key) {
        return document.path("definitions").path(key);
    }

    public JsonNode message(int id) {
        return document.path("messages").path(Integer.toString(id)).deepCopy();
    }

    public JsonNode schema(int id) {
        return fields(Integer.toString(id)).deepCopy();
    }

    public boolean mapped(int id) {
        return document.path("messages").has(Integer.toString(id));
    }

    /** 历史抓包保留自己的字典快照，之后更换本机客户端版本不会改变旧包的解释。 */
    public JsonNode snapshot() {
        return document.deepCopy();
    }

    public ObjectNode summary(ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("messageCount", document.path("messages").size());
        result.put("definitionCount", document.path("definitions").size());
        result.put("sha256", document.path("sha256").asText(""));
        result.put("sourceFile", document.path("sourceFile").asText(""));
        result.put("sourceRoot", document.path("sourceRoot").asText(""));
        return result;
    }

    /** 按名称或编号搜索；只返回显示需要的信息，字段在选中消息时读取。 */
    public JsonNode search(String keyword, ObjectMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
        String query = keyword.toLowerCase(Locale.ROOT);
        Iterator<Map.Entry<String, JsonNode>> iterator = document.path("messages").fields();
        while (iterator.hasNext() && result.size() < 300) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (entry.getKey().contains(query)
                    || entry.getValue()
                            .path("name")
                            .asText()
                            .toLowerCase(Locale.ROOT)
                            .contains(query)) {
                ObjectNode item = entry.getValue().deepCopy();
                item.put("protocolId", Integer.parseInt(entry.getKey()));
                result.add(item);
            }
        }
        return result;
    }
}
