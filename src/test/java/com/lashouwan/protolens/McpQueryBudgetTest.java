package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.mcp.AnalyzerMcpTools;
import com.lashouwan.protolens.mcp.ClientSourceReader;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 大消息只返回明确超预算错误，允许按字段查询；源码长行和越界读取具有可见边界。 */
class McpQueryBudgetTest {
    @TempDir Path directory;

    @Test
    void testLargeMessage_ShouldRequireProjectionAndPreserveSourceBounds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        Path source = directory.resolve("client");
        Files.createDirectories(source);
        Files.writeString(source.resolve("large.js"), "needle" + "x".repeat(2000) + "\nnext\n");
        Path config = directory.resolve("analyzer.json");
        mapper.writeValue(
                config.toFile(),
                mapper.createObjectNode().put("competitorPath", source.toString()));
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--config",
                            config.toString(),
                            "--data-dir",
                            directory.resolve("data").toString()
                        },
                        module,
                        module,
                        new Properties());
        var settings = new AnalyzerSettingsStore(options, mapper);
        var reader = new ClientSourceReader(settings, mapper);
        var catalog =
                new ProtocolCatalog(
                        mapper.readTree(
                                "{\"schemaVersion\":1,\"messages\":{},\"definitions\":{}}"));
        var store = new CaptureStore(directory.resolve("captures"), mapper, catalog);
        String id = store.createLive("a", "https://example.test/websocket", "");
        var message = mapper.createObjectNode().put("status", "PARTIAL").put("protocolId", 1);
        message.putObject("body").put("blob", "a".repeat(600000)).put("count", 7);
        store.appendLive(id, message);
        var tools = new AnalyzerMcpTools(store, reader, mapper);
        var args = mapper.createObjectNode().put("captureId", id).put("index", 0);
        assertTrue(tools.call("get_message", args).path("isError").asBoolean());
        var projected = tools.call("get_message", args.put("fieldPath", "/body/count"));
        assertFalse(projected.path("isError").asBoolean());
        assertEquals(
                7,
                mapper.readTree(projected.path("content").path(0).path("text").asText())
                        .path("value")
                        .asInt());
        var lines = reader.read("large.js", 1, 1);
        assertTrue(lines.path("hasMore").asBoolean());
        assertTrue(lines.path("lines").path(0).path("lineTruncated").asBoolean());
        assertEquals(1, reader.search("needle", "", 0, 1).path("matches").size());
        assertThrows(Exception.class, () -> reader.read("../analyzer.json", 1, 10));
        assertTrue(
                tools.call(
                                "search_messages",
                                mapper.createObjectNode().put("captureId", id).put("limit", "100"))
                        .path("isError")
                        .asBoolean());
    }
}
