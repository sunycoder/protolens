package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.mcp.ClientSourceReader;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 通过实际 HTTP 入口验证装配、版本参数和受限源码读取；全部文件为测试合成数据。 */
class ProtocolQueryHttpTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queryRoutesMustRequireMatchingVersionAndVerifySourceBeforeReturningText()
            throws Exception {
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        Path root = Files.createDirectories(directory.resolve("client"));
        Path data = Files.createDirectories(directory.resolve("data"));
        Path file = root.resolve("thrift_data.js");
        String source = "// synthetic only\nvar fields = {};\nfields[7] = {1: ['roleId',10]};\n";
        Files.writeString(file, source, StandardCharsets.UTF_8);
        ObjectNode document =
                (ObjectNode)
                        mapper.readTree(
                                """
{"schemaVersion":1,"messages":{"7":{"name":"GcProfile","direction":"SC"}},
 "definitions":{"7":{"1":["roleId",10]}},"fieldLines":{"7":3}}
""");
        document.put(
                        "sha256",
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(source.getBytes(StandardCharsets.UTF_8))))
                .put("sourceRoot", root.toString())
                .put("sourceFile", "thrift_data.js");
        mapper.writeValue(data.resolve("catalog.json").toFile(), document);
        Path config = directory.resolve("analyzer.json");
        mapper.writeValue(
                config.toFile(),
                mapper.createObjectNode()
                        .put("serverPort", 0)
                        .put("frontendMode", "static")
                        .put("protocolAutoLoad", false)
                        .put("autoStartCollector", false)
                        .put("autoSystemProxy", false)
                        .put("competitorPath", root.toString()));
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {"--config", config.toString(), "--data-dir", data.toString()},
                        module,
                        module,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        try (HttpClient client = HttpClient.newHttpClient()) {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            String origin = "http://127.0.0.1:" + port;
            JsonNode results =
                    mapper.readTree(
                            get(client, origin + "/api/protocol-query?q=roleId&scope=field")
                                    .body());
            assertEquals(1, results.path("total").asInt());
            String version = results.path("catalog").path("version").asText();
            String params = "?key=7&version=" + version;
            assertEquals(
                    400, get(client, origin + "/api/protocol-query/definition?key=7").statusCode());
            assertEquals(400, get(client, origin + "/api/protocol-query?offset=-1").statusCode());
            var definition = get(client, origin + "/api/protocol-query/definition" + params);
            assertEquals(200, definition.statusCode());
            assertEquals(
                    "i64",
                    mapper.readTree(definition.body())
                            .path("fields")
                            .path(0)
                            .path("type")
                            .asText());
            var preview =
                    get(
                            client,
                            origin + "/api/protocol-query/source" + params + "&path=../outside.js");
            assertEquals(200, preview.statusCode());
            assertTrue(mapper.readTree(preview.body()).path("verified").asBoolean());
            assertTrue(preview.body().contains("roleId"));
            var foreign =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/api/protocol-query"))
                                    .header("Origin", "http://untrusted.test")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode());
            Files.writeString(
                    file, "// changed version must not be returned", StandardCharsets.UTF_8);
            var mismatch = get(client, origin + "/api/protocol-query/source" + params);
            assertEquals(400, mismatch.statusCode());
            assertFalse(mismatch.body().contains("changed version must not be returned"));
            // 历史字典中恶意的相对路径不能突破当前用户配置的源码根目录。
            Path outside =
                    Files.createDirectories(directory.resolve("outside")).resolve("thrift_data.js");
            Files.writeString(outside, "outside must remain unread", StandardCharsets.UTF_8);
            var reader = new ClientSourceReader(new AnalyzerSettingsStore(options, mapper), mapper);
            ObjectNode forged = document.deepCopy().put("sourceFile", "../outside/thrift_data.js");
            assertThrows(IllegalArgumentException.class, () -> reader.protocolSource(forged, 1));
        } finally {
            app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
