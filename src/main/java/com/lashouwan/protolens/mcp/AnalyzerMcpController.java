package com.lashouwan.protolens.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.http.AnalyzerHttpSupport;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

/** 本机无状态 Streamable HTTP MCP 入口；JSON响应，无主动SSE，无会话缓存，复用现有回环来源校验。 */
public final class AnalyzerMcpController {
    private static final String VERSION = "2025-06-18";
    private static final Set<String> VERSIONS = Set.of("2025-03-26", VERSION);
    private final Vertx vertx;
    private final ObjectMapper mapper;
    private final AnalyzerMcpTools tools;

    /** 本机专用随机凭证，和采集器凭证隔离；保存在忽略目录，不进入日志或抓包查询结果。 */
    private final String token;

    private volatile int port;

    public AnalyzerMcpController(
            Vertx vertx, ObjectMapper mapper, AnalyzerMcpTools tools, Path data)
            throws IOException {
        this.vertx = vertx;
        this.mapper = mapper;
        this.tools = tools;
        Files.createDirectories(data);
        Path file = data.resolve("mcp-access.key");
        if (!Files.exists(file)) {
            String generated = UUID.randomUUID().toString() + UUID.randomUUID();
            try {
                Files.writeString(
                        file, generated, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                /* 其他启动实例已创建，读取相同本机配置。 */
            }
        }
        token = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (token.length() < 32 || token.length() > 200 || !token.matches("[a-zA-Z0-9-]+")) {
            throw new IOException("本机MCP凭证格式无效，请检查 data/mcp-access.key");
        }
    }

    public void bind(int port) {
        this.port = port;
    }

    public void register(Router router, AnalyzerHttpSupport http) {
        router.get("/api/mcp/connection").handler(context -> http.reply(context, connection()));
        router.route("/mcp")
                .handler(
                        context -> {
                            String supplied = context.request().getHeader("Authorization");
                            if (supplied == null
                                    || !MessageDigest.isEqual(
                                            supplied.getBytes(StandardCharsets.UTF_8),
                                            ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                                context.response().setStatusCode(401).end("MCP需要工作台提供的Bearer凭证");
                                return;
                            }
                            context.next();
                        });
        router.post("/mcp")
                .handler(
                        BodyHandler.create()
                                .setHandleFileUploads(false)
                                .setBodyLimit(McpLimits.REQUEST_BYTES));
        router.post("/mcp").handler(this::handle);
        router.route("/mcp")
                .handler(
                        context ->
                                context.response()
                                        .setStatusCode(405)
                                        .putHeader("Allow", "POST")
                                        .end());
    }

    private ObjectNode connection() {
        String url = "http://127.0.0.1:" + port + "/mcp";
        ObjectNode result =
                mapper.createObjectNode()
                        .put("url", url)
                        .put("transport", "streamable_http")
                        .put("readOnly", true)
                        .put("protocolVersion", VERSION);
        ObjectNode server =
                result.putObject("config")
                        .putObject("mcpServers")
                        .putObject("protolens")
                        .put("url", url);
        server.putObject("headers").put("Authorization", "Bearer " + token);
        result.put("instructions", AnalyzerMcpTools.ANALYSIS_GUIDE);
        result.set("tools", tools.list());
        return result;
    }

    private void handle(RoutingContext context) {
        String accept = context.request().getHeader("Accept");
        if (accept == null
                || !accept.contains("application/json")
                || !accept.contains("text/event-stream")) {
            context.response()
                    .setStatusCode(406)
                    .end("Accept须包含application/json和text/event-stream");
            return;
        }
        String contentType = context.request().getHeader("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            context.response().setStatusCode(415).end();
            return;
        }
        String version = context.request().getHeader("MCP-Protocol-Version");
        if (version != null && !VERSIONS.contains(version)) {
            context.response().setStatusCode(400).end("不支持的MCP-Protocol-Version");
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(context.body().buffer().getBytes());
        } catch (Exception failure) {
            send(context, 400, error(mapper.nullNode(), -32700, "JSON解析失败"));
            return;
        }
        if (request == null
                || !request.isObject()
                || !request.path("jsonrpc").asText().equals("2.0")
                || !request.path("method").isTextual()
                || (request.has("id")
                        && !(request.path("id").isTextual()
                                || request.path("id").isIntegralNumber()))) {
            send(context, 400, error(mapper.nullNode(), -32600, "无效JSON-RPC请求，不支持批次"));
            return;
        }
        if (!request.has("id")) {
            context.response().setStatusCode(202).end();
            return;
        }
        JsonNode id = request.get("id");
        JsonNode params = request.path("params");
        if (!params.isMissingNode() && !params.isObject()) {
            send(context, 200, error(id, -32602, "params须为对象"));
            return;
        }
        vertx.executeBlocking(() -> dispatch(request.path("method").asText(), params, id), false)
                .onSuccess(result -> send(context, 200, result))
                .onFailure(failure -> send(context, 200, error(id, -32603, "MCP内部查询失败")));
    }

    private ObjectNode dispatch(String method, JsonNode params, JsonNode id) {
        JsonNode result;
        switch (method) {
            case "initialize" -> {
                String requested = params.path("protocolVersion").asText();
                ObjectNode initialized =
                        mapper.createObjectNode()
                                .put(
                                        "protocolVersion",
                                        VERSIONS.contains(requested) ? requested : VERSION);
                initialized
                        .putObject("serverInfo")
                        .put("name", "protolens")
                        .put("version", "1.0.0");
                ObjectNode capabilities = initialized.putObject("capabilities");
                capabilities.putObject("tools");
                capabilities.putObject("prompts");
                initialized.put("instructions", AnalyzerMcpTools.ANALYSIS_GUIDE);
                result = initialized;
            }
            case "ping" -> result = mapper.createObjectNode();
            case "tools/list" -> {
                ObjectNode listing = mapper.createObjectNode();
                listing.set("tools", tools.list());
                result = listing;
            }
            case "tools/call" -> {
                if (!params.path("name").isTextual()) {
                    return error(id, -32602, "缺少工具name");
                }
                result =
                        tools.call(
                                params.path("name").asText(),
                                params.has("arguments")
                                        ? params.path("arguments")
                                        : mapper.createObjectNode());
            }
            case "prompts/list" -> {
                ObjectNode listing = mapper.createObjectNode();
                listing.putArray("prompts")
                        .addObject()
                        .put("name", "analyze_game_interaction")
                        .put("description", "根据抓包和客户端源码还原游戏交互，区分事实、推断和验证缺口");
                result = listing;
            }
            case "prompts/get" -> {
                if (!"analyze_game_interaction".equals(params.path("name").asText())) {
                    return error(id, -32602, "未知提示词");
                }
                ObjectNode prompt = mapper.createObjectNode().put("description", "游戏交互证据分析");
                prompt.putArray("messages")
                        .addObject()
                        .put("role", "user")
                        .putObject("content")
                        .put("type", "text")
                        .put("text", AnalyzerMcpTools.ANALYSIS_GUIDE);
                result = prompt;
            }
            default -> {
                return error(id, -32601, "不支持的方法：" + method);
            }
        }
        ObjectNode response = mapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private void send(RoutingContext context, int status, ObjectNode response) {
        if (context.response().closed() || context.response().ended()) {
            return;
        }
        try {
            context.response()
                    .setStatusCode(status)
                    .putHeader("Content-Type", "application/json; charset=utf-8")
                    .end(mapper.writeValueAsString(response));
        } catch (Exception failure) {
            context.response().setStatusCode(500).end();
        }
    }
}
