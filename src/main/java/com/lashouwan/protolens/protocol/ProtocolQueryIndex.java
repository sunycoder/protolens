package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.enums.MessageDirection;
import com.lashouwan.protolens.enums.ProtocolDefinitionKind;
import com.lashouwan.protolens.enums.ProtocolSearchScope;
import com.lashouwan.protolens.enums.ThriftType;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一份字典对应一个只读协议查询索引；换版时创建新索引，旧查询继续使用原快照。
 *
 * <p>字段只在所属定义中索引一次，再沿反向结构引用向上查找协议。循环引用用已访问集合终止， 不为每个协议递归复制一份全部嵌套字段，也不因循环或深度限制静默漏掉协议结果。
 */
public final class ProtocolQueryIndex {
    private static final String OMITTED_PATH = "\u0000";
    private final JsonNode source;
    private final ObjectMapper mapper;
    private final Map<String, ProtocolCatalogEntry> entries = new LinkedHashMap<>();

    /** 被引用的结构体键 → 直接引用它的定义和字段路径。 */
    private final Map<String, List<ProtocolCatalogReference>> parents = new HashMap<>();

    /** 规范化字段名 → 声明该字段的定义及其字段路径。 */
    private final Map<String, List<ProtocolCatalogReference>> fieldOwners = new HashMap<>();

    private final String version;

    ProtocolQueryIndex(JsonNode source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
        try {
            version =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(mapper.writeValueAsBytes(source)));
        } catch (Exception failure) {
            throw new IllegalStateException("无法计算协议字典指纹", failure);
        }
        source.path("definitions")
                .fields()
                .forEachRemaining(
                        item -> {
                            String key = item.getKey();
                            JsonNode message = source.path("messages").path(key);
                            boolean isMessage = !message.isMissingNode();
                            int line =
                                    source.path("fieldLines")
                                            .path(key)
                                            .asInt(message.path("line").asInt());
                            ProtocolCatalogEntry entry =
                                    new ProtocolCatalogEntry(
                                            key,
                                            isMessage ? message.path("name").asText(key) : key,
                                            isMessage
                                                    ? message.path("direction").asText("UNKNOWN")
                                                    : "",
                                            isMessage,
                                            item.getValue(),
                                            line);
                            entries.put(key, entry);
                            item.getValue()
                                    .fields()
                                    .forEachRemaining(
                                            field -> {
                                                String name =
                                                        field.getValue()
                                                                .path(0)
                                                                .asText(field.getKey());
                                                fieldOwners
                                                        .computeIfAbsent(
                                                                name.toLowerCase(Locale.ROOT),
                                                                ignored -> new ArrayList<>())
                                                        .add(
                                                                new ProtocolCatalogReference(
                                                                        key, pointer(name)));
                                                indexReferences(
                                                        key,
                                                        field.getValue().path(1),
                                                        pointer(name),
                                                        0);
                                            });
                        });
    }

    boolean belongsTo(JsonNode document) {
        return source == document;
    }

    public String version() {
        return version;
    }

    public ObjectNode catalog() {
        return mapper.createObjectNode()
                .put("version", version)
                .put("sha256", source.path("sha256").asText())
                .put("sourceFile", source.path("sourceFile").asText())
                .put("sourceRoot", source.path("sourceRoot").asText())
                .put("messageCount", source.path("messages").size())
                .put("definitionCount", entries.size());
    }

    /** 后续详情和源码查询必须携带搜索时的版本，换版后显式要求刷新，不能用旧 ID 打开新定义。 */
    public void requireVersion(String expected) {
        if (!version.equals(expected)) {
            throw new IllegalArgumentException("协议字典已变化，请刷新查询后重试");
        }
    }

    /** 全量匹配后分页，返回真实总数。多个词按且合并，方向筛选只保留对应消息，不把结构体冒充收发消息。 */
    public ObjectNode search(
            String query, ProtocolSearchScope scope, String direction, int offset, int limit) {
        if (query.length() > ProtocolQueryLimits.MAX_QUERY_LENGTH
                || offset < 0
                || limit < 1
                || limit > ProtocolQueryLimits.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("协议查询长度或分页参数超出范围");
        }
        if (!Set.of("", MessageDirection.CS.name(), MessageDirection.SC.name(), "UNKNOWN")
                .contains(direction)) {
            throw new IllegalArgumentException("协议方向无效");
        }
        String text = query.strip().toLowerCase(Locale.ROOT);
        String[] terms = text.isEmpty() ? new String[0] : text.split("\\s+");
        if (terms.length > ProtocolQueryLimits.MAX_QUERY_TERMS) {
            throw new IllegalArgumentException("搜索词最多 8 个");
        }
        Map<String, ProtocolSearchHit> matches = new HashMap<>();
        if (terms.length == 0) {
            for (ProtocolCatalogEntry entry : entries.values()) {
                if (scope != ProtocolSearchScope.STRUCTURE ? entry.message() : !entry.message()) {
                    matches.put(entry.key(), new ProtocolSearchHit(100, "全部定义", ""));
                }
            }
        }
        for (int index = 0; index < terms.length; index++) {
            Map<String, ProtocolSearchHit> found = match(terms[index], scope);
            if (index == 0) {
                matches.putAll(found);
            } else {
                var iterator = matches.entrySet().iterator();
                while (iterator.hasNext()) {
                    var item = iterator.next();
                    ProtocolSearchHit next = found.get(item.getKey());
                    if (next == null) {
                        iterator.remove();
                    } else {
                        ProtocolSearchHit previous = item.getValue();
                        item.setValue(
                                new ProtocolSearchHit(
                                        previous.rank() + next.rank(),
                                        previous.reason() + "；" + next.reason(),
                                        previous.path().isEmpty() ? next.path() : previous.path()));
                    }
                }
            }
        }
        List<Map.Entry<String, ProtocolSearchHit>> sorted = new ArrayList<>();
        for (var match : matches.entrySet()) {
            ProtocolCatalogEntry entry = entries.get(match.getKey());
            if (direction.isEmpty() || direction.equals(entry.direction())) {
                sorted.add(match);
            }
        }
        sorted.sort(
                Comparator.comparingInt(
                                (Map.Entry<String, ProtocolSearchHit> item) ->
                                        item.getValue().rank())
                        .thenComparing(item -> !entries.get(item.getKey()).message())
                        .thenComparing(
                                item -> entries.get(item.getKey()).name(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Map.Entry::getKey));
        ObjectNode result =
                mapper.createObjectNode()
                        .put("total", sorted.size())
                        .put("offset", offset)
                        .put("limit", limit);
        result.set("catalog", catalog());
        ArrayNode items = result.putArray("items");
        int end = (int) Math.min(sorted.size(), (long) offset + limit);
        for (int index = Math.min(offset, sorted.size()); index < end; index++) {
            var hit = sorted.get(index);
            items.add(
                    describe(entries.get(hit.getKey()))
                            .put("matchReason", hit.getValue().reason())
                            .put("matchPath", hit.getValue().path()));
        }
        return result;
    }

    private Map<String, ProtocolSearchHit> match(String term, ProtocolSearchScope scope) {
        Map<String, ProtocolSearchHit> result = new HashMap<>();
        if (scope == ProtocolSearchScope.ALL || scope == ProtocolSearchScope.NAME) {
            for (ProtocolCatalogEntry entry : entries.values()) {
                if (!entry.message()) {
                    continue;
                }
                String name = entry.name().toLowerCase(Locale.ROOT);
                if (entry.key().equals(term)) {
                    offer(result, entry.key(), 0, "协议 ID 精确匹配", "");
                } else if (name.equals(term)) {
                    offer(result, entry.key(), 10, "协议名称精确匹配", "");
                } else if (name.startsWith(term)) {
                    offer(result, entry.key(), 20, "协议名称前缀匹配", "");
                } else if (name.contains(term) || entry.key().contains(term)) {
                    offer(result, entry.key(), 30, "协议名称 / ID 包含", "");
                }
            }
        }
        if (scope == ProtocolSearchScope.ALL || scope == ProtocolSearchScope.FIELD) {
            List<ProtocolQuerySeed> seeds = new ArrayList<>();
            for (var field : fieldOwners.entrySet()) {
                if (!field.getKey().contains(term)) {
                    continue;
                }
                for (ProtocolCatalogReference owner : field.getValue()) {
                    seeds.add(
                            new ProtocolQuerySeed(
                                    owner.ownerKey(),
                                    owner.path(),
                                    "字段 "
                                            + owner.path()
                                                    .substring(1)
                                                    .replace("~1", "/")
                                                    .replace("~0", "~"),
                                    50));
                }
            }
            spread(result, seeds);
        }
        if (scope == ProtocolSearchScope.ALL || scope == ProtocolSearchScope.STRUCTURE) {
            List<ProtocolQuerySeed> seeds = new ArrayList<>();
            for (ProtocolCatalogEntry entry : entries.values()) {
                if (!entry.message() && entry.name().toLowerCase(Locale.ROOT).contains(term)) {
                    seeds.add(new ProtocolQuerySeed(entry.key(), "", "结构体 " + entry.name(), 40));
                }
            }
            spread(result, seeds);
        }
        return result;
    }

    /** 反向广度遍历保留最短的一条定位路径；每次传播最多访问每个定义一次，循环不会无限展开。 */
    private void spread(Map<String, ProtocolSearchHit> result, List<ProtocolQuerySeed> seeds) {
        var queue = new ArrayDeque<>(seeds);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            ProtocolQuerySeed current = queue.removeFirst();
            if (!visited.add(current.key())) {
                continue;
            }
            if (entries.containsKey(current.key())) {
                offer(result, current.key(), current.rank(), current.reason(), current.path());
            }
            for (ProtocolCatalogReference parent : parents.getOrDefault(current.key(), List.of())) {
                String combined = parent.path() + current.path();
                // 超长路径仍保留查询结果和明确标识；不会将截断路径当成可精确定位的字段。
                if (OMITTED_PATH.equals(current.path())
                        || combined.length() > ProtocolQueryLimits.MAX_MATCH_PATH_LENGTH) {
                    combined = OMITTED_PATH;
                }
                String reason =
                        current.reason().startsWith("通过结构引用命中：")
                                ? current.reason()
                                : "通过结构引用命中：" + current.reason();
                queue.addLast(
                        new ProtocolQuerySeed(
                                parent.ownerKey(), combined, reason, current.rank() + 20));
            }
        }
    }

    private void offer(
            Map<String, ProtocolSearchHit> result,
            String key,
            int rank,
            String reason,
            String path) {
        ProtocolSearchHit previous = result.get(key);
        if (previous == null || rank < previous.rank()) {
            boolean omitted =
                    OMITTED_PATH.equals(path)
                            || path.length() > ProtocolQueryLimits.MAX_MATCH_PATH_LENGTH;
            result.put(
                    key,
                    new ProtocolSearchHit(
                            rank, omitted ? reason + "（路径过长，已省略）" : reason, omitted ? "" : path));
        }
    }

    /** 返回当前定义的一页字段和有界引用列表，不递归下发整个结构体图。 */
    public ObjectNode definition(String key, int fieldOffset) {
        return definition(key, fieldOffset, "");
    }

    /** 传入字段路径时定位到顶层字段所在页；路径指向结构体内部时由页面逐层按需展开。 */
    public ObjectNode definition(String key, int fieldOffset, String matchPath) {
        ProtocolCatalogEntry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("该字典中没有此协议或结构体");
        }
        if (fieldOffset < 0) {
            throw new IllegalArgumentException("字段分页无效");
        }
        ObjectNode result = describe(entry);
        result.set("catalog", catalog());
        result.put("sourceLine", entry.line());
        ArrayNode fields = result.putArray("fields");
        ObjectNode raw = result.putObject("rawDefinition");
        List<Map.Entry<String, JsonNode>> ordered = new ArrayList<>();
        entry.fields().fields().forEachRemaining(ordered::add);
        ordered.sort(Comparator.comparingInt(item -> Integer.parseInt(item.getKey())));
        if (fieldOffset == 0 && matchPath.startsWith("/")) {
            String target =
                    matchPath.substring(1).split("/", 2)[0].replace("~1", "/").replace("~0", "~");
            for (int index = 0; index < ordered.size(); index++) {
                if (target.equals(ordered.get(index).getValue().path(0).asText())) {
                    fieldOffset =
                            index
                                    / ProtocolQueryLimits.FIELD_PAGE_SIZE
                                    * ProtocolQueryLimits.FIELD_PAGE_SIZE;
                    break;
                }
            }
        }
        result.put("fieldOffset", fieldOffset);
        int end =
                (int)
                        Math.min(
                                ordered.size(),
                                (long) fieldOffset + ProtocolQueryLimits.FIELD_PAGE_SIZE);
        for (int index = Math.min(fieldOffset, ordered.size()); index < end; index++) {
            var item = ordered.get(index);
            String name = item.getValue().path(0).asText(item.getKey());
            ObjectNode field =
                    fields.addObject()
                            .put("id", item.getKey())
                            .put("name", name)
                            .put("path", pointer(name))
                            .put("type", typeName(item.getValue().path(1), 0));
            ArrayNode structures = field.putArray("structures");
            collectStructures(item.getValue().path(1), structures, "", 0);
            raw.set(item.getKey(), item.getValue().deepCopy());
        }
        result.put("fieldsHaveMore", end < ordered.size())
                .put("fieldPageSize", ProtocolQueryLimits.FIELD_PAGE_SIZE);
        ArrayNode references = result.putArray("references");
        List<ProtocolCatalogReference> direct = parents.getOrDefault(key, List.of());
        for (int index = 0;
                index < Math.min(direct.size(), ProtocolQueryLimits.MAX_REFERENCES);
                index++) {
            ProtocolCatalogReference reference = direct.get(index);
            ProtocolCatalogEntry owner = entries.get(reference.ownerKey());
            if (owner != null) {
                references.add(describe(owner).put("path", reference.path()));
            }
        }
        result.put("referenceCount", direct.size());
        Map<String, ProtocolSearchHit> ancestors = new HashMap<>();
        spread(ancestors, List.of(new ProtocolQuerySeed(key, "", "结构引用", 0)));
        ArrayNode protocols = result.putArray("usedByProtocols");
        int protocolCount = 0;
        for (ProtocolCatalogEntry owner : entries.values()) {
            if (owner.message() && !owner.key().equals(key) && ancestors.containsKey(owner.key())) {
                protocolCount++;
                if (protocols.size() < ProtocolQueryLimits.MAX_REFERENCES) {
                    protocols.add(describe(owner).put("path", ancestors.get(owner.key()).path()));
                }
            }
        }
        return result.put("usedByProtocolCount", protocolCount);
    }

    private ObjectNode describe(ProtocolCatalogEntry entry) {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("key", entry.key())
                        .put("name", entry.name())
                        .put(
                                "kind",
                                entry.message()
                                        ? ProtocolDefinitionKind.MESSAGE.code()
                                        : ProtocolDefinitionKind.STRUCTURE.code())
                        .put("direction", entry.direction())
                        .put("fieldCount", entry.fields().size());
        if (entry.message()) {
            result.put("protocolId", Integer.parseInt(entry.key()));
        }
        return result;
    }

    private void indexReferences(String owner, JsonNode type, String path, int depth) {
        if (!type.isArray()) {
            return;
        }
        if (depth > ProtocolQueryLimits.MAX_TYPE_DEPTH) {
            throw new IllegalArgumentException("协议容器类型嵌套过深");
        }
        int code = type.path(0).asInt();
        if (code == ThriftType.STRUCT.code()) {
            parents.computeIfAbsent(type.path(1).asText(), ignored -> new ArrayList<>())
                    .add(new ProtocolCatalogReference(owner, path));
        } else if (code == ThriftType.MAP.code()) {
            indexReferences(owner, type.path(1), path + "/{key}", depth + 1);
            indexReferences(owner, type.path(2), path + "/{value}", depth + 1);
        } else if (code == ThriftType.LIST.code() || code == ThriftType.SET.code()) {
            indexReferences(owner, type.path(1), path + "/[]", depth + 1);
        }
    }

    private void collectStructures(JsonNode type, ArrayNode result, String suffix, int depth) {
        if (!type.isArray() || depth > ProtocolQueryLimits.MAX_TYPE_DEPTH) {
            return;
        }
        int code = type.path(0).asInt();
        if (code == ThriftType.STRUCT.code()) {
            result.addObject()
                    .put("key", type.path(1).asText())
                    .put("name", type.path(1).asText())
                    .put("pathSuffix", suffix);
        } else if (code == ThriftType.MAP.code()) {
            collectStructures(type.path(1), result, suffix + "/{key}", depth + 1);
            collectStructures(type.path(2), result, suffix + "/{value}", depth + 1);
        } else if (code == ThriftType.LIST.code() || code == ThriftType.SET.code()) {
            collectStructures(type.path(1), result, suffix + "/[]", depth + 1);
        }
    }

    private String typeName(JsonNode type, int depth) {
        if (depth > ProtocolQueryLimits.MAX_TYPE_DEPTH) {
            return "类型嵌套达到上限";
        }
        int code = type.isArray() ? type.path(0).asInt() : type.asInt();
        if (code == ThriftType.STRUCT.code()) {
            return type.path(1).asText();
        }
        if (code == ThriftType.MAP.code()) {
            return "map<"
                    + typeName(type.path(1), depth + 1)
                    + ", "
                    + typeName(type.path(2), depth + 1)
                    + ">";
        }
        if (code == ThriftType.LIST.code() || code == ThriftType.SET.code()) {
            return (code == ThriftType.LIST.code() ? "list<" : "set<")
                    + typeName(type.path(1), depth + 1)
                    + ">";
        }
        if (code == ThriftType.STRING.code()) {
            return type.isArray() && type.path(1).asInt() == 1 ? "binary" : "string";
        }
        for (ThriftType value : ThriftType.values()) {
            if (value.code() == code) {
                return value.name().toLowerCase(Locale.ROOT);
            }
        }
        return "未知类型(" + code + ")";
    }

    private static String pointer(String key) {
        return "/" + key.replace("~", "~0").replace("/", "~1");
    }
}
