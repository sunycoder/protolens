package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.enums.ProtocolSearchScope;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolQueryIndex;
import com.lashouwan.protolens.protocol.ProtocolQueryService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 使用独立合成字典验证全量查询、结构循环和历史版本，不读取真实竞品或账号数据。 */
class ProtocolQueryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode document() throws Exception {
        return (ObjectNode)
                mapper.readTree(
                        """
{"schemaVersion":1,"sha256":"fixture","sourceFile":"thrift_data.js",
 "messages":{"0":{"name":"CgZero","direction":"CS"},"12":{"name":"GcPlayerInfo","direction":"SC"},
   "120":{"name":"CgOther","direction":"CS"},"99":{"name":"Mystery","direction":"UNKNOWN"}},
 "definitions":{"0":{},"12":{"1":["playerInfo",[12,"PsPlayer"]],"2":["heroes",[15,[12,"PsHero"]]],
   "3":["mapping",[13,8,[12,"PsHero"]]],"4":["binary",[11,1]]},"120":{"1":["playerId",10]},"99":{},
   "PsPlayer":{"1":["roleId",10],"2":["linked",[12,"PsHero"]]},
   "PsHero":{"1":["heroId",10],"2":["owner",[12,"PsPlayer"]]}}}
""");
    }

    private ProtocolQueryIndex index() throws Exception {
        return new ProtocolCatalog(document()).queryIndex(mapper);
    }

    private Set<String> keys(JsonNode result) {
        Set<String> values = new HashSet<>();
        result.path("items").forEach(item -> values.add(item.path("key").asText()));
        return values;
    }

    /** ID 0 合法，纯数字精确协议排在包含该数字的协议之前；未知方向不能被当作 SC。 */
    @Test
    void numericIdDirectionAndUnknownDirectionMustRemainExact() throws Exception {
        var index = index();
        assertEquals(
                0,
                index.search("0", ProtocolSearchScope.NAME, "", 0, 50)
                        .path("items")
                        .path(0)
                        .path("protocolId")
                        .asInt(-1));
        assertEquals(
                "12",
                index.search("12", ProtocolSearchScope.ALL, "", 0, 50)
                        .path("items")
                        .path(0)
                        .path("key")
                        .asText());
        assertEquals(Set.of("12"), keys(index.search("", ProtocolSearchScope.ALL, "SC", 0, 50)));
        assertEquals(
                Set.of("99"), keys(index.search("", ProtocolSearchScope.ALL, "UNKNOWN", 0, 50)));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.search("", ProtocolSearchScope.ALL, "BAD", 0, 50));
    }

    /** 字段通过容器和循环引用仍可找到所属协议，路径指出实际引用位置；原字典不被展开或改写。 */
    @Test
    void nestedFieldsContainersAndCyclesMustReturnPathsAndReferences() throws Exception {
        var index = index();
        var result = index.search("heroId", ProtocolSearchScope.FIELD, "", 0, 50);
        assertTrue(keys(result).containsAll(Set.of("12", "PsHero", "PsPlayer")));
        JsonNode protocol = null;
        for (JsonNode item : result.path("items")) {
            if ("12".equals(item.path("key").asText())) {
                protocol = item;
            }
        }
        assertNotNull(protocol);
        assertEquals("/heroes/[]/heroId", protocol.path("matchPath").asText());
        var hero = index.definition("PsHero", 0);
        assertEquals(3, hero.path("referenceCount").asInt());
        assertEquals(1, hero.path("usedByProtocolCount").asInt());
        assertEquals("12", hero.path("usedByProtocols").path(0).path("key").asText());
        var definition = index.definition("12", 0);
        assertEquals("map<i32, PsHero>", definition.path("fields").path(2).path("type").asText());
        assertEquals("binary", definition.path("fields").path(3).path("type").asText());
        assertEquals(
                "/{value}",
                definition
                        .path("fields")
                        .path(2)
                        .path("structures")
                        .path(0)
                        .path("pathSuffix")
                        .asText());
    }

    /** 多词按且组合；结构体范围同时返回结构体和真实引用者，不推断请求响应。 */
    @Test
    void combinedTermsAndStructureSearchMustUseActualGraph() throws Exception {
        var index = index();
        assertEquals(
                Set.of("12"),
                keys(index.search("GcPlayer roleId", ProtocolSearchScope.ALL, "", 0, 50)));
        assertEquals(
                Set.of(), keys(index.search("CgOther heroId", ProtocolSearchScope.ALL, "", 0, 50)));
        assertTrue(
                keys(index.search("pshero", ProtocolSearchScope.STRUCTURE, "", 0, 50))
                        .containsAll(Set.of("PsHero", "PsPlayer", "12")));
    }

    /** 超过原先 300 条结果时仍能分页遍历完整集合，页外偏移返回空页而非伪造条数。 */
    @Test
    void paginationMustExposeResultsBeyondThreeHundred() throws Exception {
        ObjectNode document = document();
        for (int index = 1000; index < 1350; index++) {
            ((ObjectNode) document.path("messages"))
                    .putObject(Integer.toString(index))
                    .put("name", "GcBatch" + index)
                    .put("direction", "SC");
            ((ObjectNode) document.path("definitions")).putObject(Integer.toString(index));
        }
        var index = new ProtocolCatalog(document).queryIndex(mapper);
        var page = index.search("GcBatch", ProtocolSearchScope.NAME, "", 300, 50);
        assertEquals(350, page.path("total").asInt());
        assertEquals(50, page.path("items").size());
        assertTrue(
                index.search("GcBatch", ProtocolSearchScope.NAME, "", Integer.MAX_VALUE, 50)
                        .path("items")
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> index.search("", ProtocolSearchScope.ALL, "", -1, 50));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.search("x".repeat(201), ProtocolSearchScope.ALL, "", 0, 50));
    }

    /** 字段位于第二页时搜索结果定位到该页，不因为只加载前一百项而显示“没有字段”。 */
    @Test
    void matchedFieldMustLocateItsDefinitionPageAndKeepEscapedPaths() throws Exception {
        ObjectNode document = document();
        ObjectNode fields = (ObjectNode) document.path("definitions").path("0");
        for (int index = 1; index <= 140; index++) {
            fields.putArray(Integer.toString(index)).add("field" + index).add(8);
        }
        fields.putArray("141").add("a/b~c").add(11);
        var index = new ProtocolCatalog(document).queryIndex(mapper);
        var page = index.definition("0", 0, "/field130");
        assertEquals(100, page.path("fieldOffset").asInt());
        assertEquals(41, page.path("fields").size());
        assertEquals("/a~1b~0c", page.path("fields").path(40).path("path").asText());
        assertEquals(41, page.path("rawDefinition").size());
        assertThrows(IllegalArgumentException.class, () -> index.definition("missing", 0));
    }

    /** 换版后已发布索引继续解释旧定义，新详情请求不能沿用旧版本令牌。 */
    @Test
    void catalogReplacementMustKeepPreviousIndexAndRejectStaleVersion() throws Exception {
        ProtocolCatalog catalog = new ProtocolCatalog(document());
        var old = catalog.queryIndex(mapper);
        ObjectNode replacement = document();
        ((ObjectNode) replacement.path("messages").path("12")).put("name", "GcDifferentVersion");
        catalog.replace(replacement);
        var current = catalog.queryIndex(mapper);
        assertNotEquals(old.version(), current.version());
        assertEquals("GcPlayerInfo", old.definition("12", 0).path("name").asText());
        assertEquals("GcDifferentVersion", current.definition("12", 0).path("name").asText());
        assertThrows(IllegalArgumentException.class, () -> current.requireVersion(old.version()));
    }

    /** 同 ID 在历史记录和当前字典中含义不同时各自解释；只查询不会变更消息、备注或字典。 */
    @Test
    void recordCatalogMustRemainIsolatedAndOccurrencesMustUseRetainedMessages() throws Exception {
        ProtocolCatalog catalog = new ProtocolCatalog(document());
        CaptureStore captures = new CaptureStore(directory, mapper, catalog);
        String id = captures.createLive("c", "test", "");
        captures.appendLive(
                id,
                mapper.createObjectNode()
                        .put("protocolId", 12)
                        .put("connection", "c")
                        .put("direction", "SC"));
        var service = new ProtocolQueryService(catalog, captures, null, mapper);
        var sameVersion = service.search("", "12", "name", "", 0, 50);
        assertEquals(
                id,
                service.definition(
                                "",
                                "12",
                                sameVersion.path("catalog").path("version").asText(),
                                0,
                                "",
                                id)
                        .path("linkedCaptureId")
                        .asText());
        ObjectNode replacement = document();
        ((ObjectNode) replacement.path("messages").path("12")).put("name", "GcNewVersion");
        catalog.replace(replacement);
        var record = service.search(id, "12", "name", "", 0, 50);
        var current = service.search("", "12", "name", "", 0, 50);
        assertEquals("GcPlayerInfo", record.path("items").path(0).path("name").asText());
        assertEquals("GcNewVersion", current.path("items").path(0).path("name").asText());
        assertEquals(
                1,
                service.definition(
                                id,
                                "12",
                                record.path("catalog").path("version").asText(),
                                0,
                                "",
                                "")
                        .path("occurrences")
                        .path("count")
                        .asInt());
        var mismatched =
                service.definition(
                        "", "12", current.path("catalog").path("version").asText(), 0, "", id);
        assertFalse(mismatched.has("occurrences"));
        assertTrue(mismatched.has("linkNotice"));
        assertEquals(1, captures.describe(id).path("messageCount").asInt());
    }
}
