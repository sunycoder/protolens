package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 通过真实回环 HTTP 验证 MCP 握手、认证、只读工具、抓包查询和源码读取，不连接游戏网络。 */
class McpIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private String origin;
    private String authorization;
    private HttpClient client;

    @Test
    void testMcp_ShouldNegotiateQueryEvidenceAndRejectUnsafeRequests() throws Exception {
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        Path source = directory.resolve("client");
        Files.createDirectories(source);
        Files.writeString(
                source.resolve("catapult.js"),
                "// siege example\nfunction createCatapult() { return 2364; }\n");
        Path secret = directory.resolve("outside.js");
        Files.writeString(secret, "not accessible");
        Path config = directory.resolve("config.json");
        mapper.writeValue(
                config.toFile(),
                mapper.createObjectNode()
                        .put("serverPort", 0)
                        .put("frontendMode", "static")
                        .put("protocolAutoLoad", false)
                        .put("competitorPath", source.toString()));
        Path data = directory.resolve("data");
        Files.createDirectories(data);
        mapper.writeValue(
                data.resolve("catalog.json").toFile(),
                mapper.readTree(
                        """
{"schemaVersion":1,"messages":{"2364":{"name":"CgAllianceTechDonate"}},
"definitions":{"2364":{"1":["techGroupId",8],"2":["isUseCp",2],"3":["level",8]}}}
"""));
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {"--config", config.toString(), "--data-dir", data.toString()},
                        module,
                        module,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            origin = "http://127.0.0.1:" + port;
            JsonNode connection = json(request("GET", "/api/mcp/connection", null, null));
            authorization =
                    connection
                            .path("config")
                            .path("mcpServers")
                            .path("protolens")
                            .path("headers")
                            .path("Authorization")
                            .asText();
            assertTrue(authorization.startsWith("Bearer "));
            assertEquals(origin + "/mcp", connection.path("url").asText());
            ObjectNode init =
                    rpc(
                            "initialize",
                            mapper.createObjectNode().put("protocolVersion", "2025-11-25"));
            assertEquals(401, request("POST", "/mcp", init.toString(), null).statusCode());
            JsonNode initialized = json(request("POST", "/mcp", init.toString(), authorization));
            assertEquals("2025-06-18", initialized.path("result").path("protocolVersion").asText());
            assertTrue(initialized.path("result").path("capabilities").has("tools"));
            ObjectNode notification =
                    mapper.createObjectNode()
                            .put("jsonrpc", "2.0")
                            .put("method", "notifications/initialized");
            assertEquals(
                    202,
                    request("POST", "/mcp", notification.toString(), authorization).statusCode());
            assertEquals(405, request("GET", "/mcp", null, authorization).statusCode());
            HttpResponse<String> malformed = request("POST", "/mcp", "{", authorization);
            assertEquals(400, malformed.statusCode());
            assertEquals(
                    -32700, mapper.readTree(malformed.body()).path("error").path("code").asInt());
            assertEquals(
                    -32601,
                    json(request(
                                    "POST",
                                    "/mcp",
                                    rpc("unknown", mapper.createObjectNode()).toString(),
                                    authorization))
                            .path("error")
                            .path("code")
                            .asInt());
            JsonNode listing =
                    json(
                            request(
                                    "POST",
                                    "/mcp",
                                    rpc("tools/list", mapper.createObjectNode()).toString(),
                                    authorization));
            assertEquals(9, listing.path("result").path("tools").size());
            for (JsonNode tool : listing.path("result").path("tools")) {
                assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
            }
            request("POST", "/api/live/start", "{}", null);
            JsonNode collector = json(request("GET", "/api/live/config", null, null));
            String batch =
                    """
{"source":"aab4a22b-e252-4b3b-b850-5f6e108142e0","events":[
  {"sequence":1,"type":"open","connection":"sample","endpoint":"https://example.test/websocket","extensions":""},
  {"sequence":2,"type":"packet","connection":"sample","direction":"CS","time":"2026-11-16T01:00:00Z","packetBase64":"AAIAUQk8AAAABxXyDxIVCAA="}]}
""";
            HttpRequest ingest =
                    HttpRequest.newBuilder(URI.create(origin + "/api/live/batches"))
                            .header("Content-Type", "application/json")
                            .header("X-Capture-Token", collector.path("token").asText())
                            .POST(HttpRequest.BodyPublishers.ofString(batch))
                            .build();
            assertEquals(
                    200, client.send(ingest, HttpResponse.BodyHandlers.ofString()).statusCode());
            JsonNode records = call("list_captures", mapper.createObjectNode());
            String id = records.path("items").path(0).path("id").asText();
            ObjectNode args = mapper.createObjectNode().put("captureId", id);
            assertEquals(1, call("get_capture_overview", args).path("messageCount").asInt());
            assertEquals(
                    1,
                    call("search_messages", args.deepCopy().put("query", "Donate"))
                            .path("total")
                            .asInt());
            JsonNode message = call("get_message", args.deepCopy().put("index", 0));
            assertEquals(1017, message.path("body").path("techGroupId").asInt());
            assertFalse(message.has("packetHex"));
            assertEquals(
                    4,
                    call(
                                    "get_message",
                                    args.deepCopy().put("index", 0).put("fieldPath", "/body/level"))
                            .path("value")
                            .asInt());
            assertEquals(
                    1,
                    call("get_message_context", args.deepCopy().put("index", 0))
                            .path("items")
                            .size());
            assertEquals(1, call("find_protocols", args.deepCopy().put("query", "Donate")).size());
            assertEquals(
                    "techGroupId",
                    call("get_protocol_definition", args.deepCopy().put("key", "2364"))
                            .path("fields")
                            .path("1")
                            .path(0)
                            .asText());
            assertEquals(
                    2,
                    call(
                                    "search_client_source",
                                    mapper.createObjectNode().put("query", "createCatapult"))
                            .path("matches")
                            .path(0)
                            .path("line")
                            .asInt());
            assertEquals(
                    2,
                    call("read_client_source", mapper.createObjectNode().put("path", "catapult.js"))
                            .path("lines")
                            .size());
            assertToolError(
                    "read_client_source", mapper.createObjectNode().put("path", "../outside.js"));
            assertToolError("search_messages", args.deepCopy().put("limit", 99999));
            assertToolError("get_message", args.deepCopy().put("index", 999));
            assertToolError("delete_capture", args);
            assertToolError("search_messages", args.deepCopy().put("extra", "unknown"));
            // Origin保护同样覆盖MCP，不因Bearer凭证放宽跨站访问。
            HttpRequest foreign =
                    HttpRequest.newBuilder(URI.create(origin + "/mcp"))
                            .header("Origin", "http://evil.test")
                            .header("Authorization", authorization)
                            .POST(HttpRequest.BodyPublishers.ofString(init.toString()))
                            .build();
            assertEquals(
                    403, client.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            client.close();
            try {
                app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private ObjectNode rpc(String method, JsonNode params) {
        ObjectNode value =
                mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        value.set("params", params);
        return value;
    }

    private JsonNode call(String name, ObjectNode args) throws Exception {
        JsonNode result = toolResult(name, args);
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return mapper.readTree(result.path("content").path(0).path("text").asText());
    }

    private void assertToolError(String name, ObjectNode args) throws Exception {
        assertTrue(toolResult(name, args).path("isError").asBoolean());
    }

    private JsonNode toolResult(String name, ObjectNode args) throws Exception {
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", args);
        return json(request("POST", "/mcp", rpc("tools/call", params).toString(), authorization))
                .path("result");
    }

    private HttpResponse<String> request(String method, String path, String body, String auth)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(origin + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json, text/event-stream");
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
}
