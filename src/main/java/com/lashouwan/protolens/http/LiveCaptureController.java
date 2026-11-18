package com.lashouwan.protolens.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 本机实时控制、采集器连接配置与 SSE 通知；接入凭证仅属于当前本机后端进程。 */
public final class LiveCaptureController implements AutoCloseable {
    private final Vertx vertx;
    private final ObjectMapper mapper;
    private final AnalyzerHttpSupport http;
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();

    /** 多个 HTTP 连接可能属于不同事件循环，订阅集合并发维护；状态读取在 worker 执行。 */
    private final Set<HttpServerResponse> subscribers = ConcurrentHashMap.newKeySet();

    private final LiveCaptureService service;
    private final long keepAlive;

    /** HTTP 实际绑定端口，允许测试或命令行使用 port=0，由应用在就绪后发布。 */
    private volatile int boundPort;

    public LiveCaptureController(
            Vertx vertx,
            CaptureStore captures,
            ProtocolCatalog catalog,
            ObjectMapper mapper,
            AnalyzerHttpSupport http) {
        this.vertx = vertx;
        this.mapper = mapper;
        this.http = http;
        service =
                new LiveCaptureService(
                        captures,
                        catalog,
                        mapper,
                        status -> {
                            String event = "event: status\ndata: " + status.toString() + "\n\n";
                            vertx.runOnContext(ignored -> broadcast(event));
                        });
        keepAlive =
                vertx.setPeriodic(
                        AnalyzerLimits.SSE_HEARTBEAT_MILLIS,
                        ignored -> broadcast(": keep-alive\n\n"));
    }

    public LiveCaptureService service() {
        return service;
    }

    public void register(Router router, int port) {
        boundPort = port;
        router.get("/api/live/status").handler(context -> http.worker(context, service::status));
        router.post("/api/live/clear")
                .handler(context -> http.worker(context, service::clearSession));
        router.post("/api/live/start").handler(context -> http.worker(context, service::start));
        router.post("/api/live/stop")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            JsonObject input = context.body().asJsonObject();
                                            return service.stop(
                                                    input != null
                                                            && input.getBoolean("save", false),
                                                    input == null
                                                            ? ""
                                                            : input.getString("name", ""));
                                        }));
        router.post("/api/live/batches")
                .handler(
                        context -> {
                            String supplied = context.request().getHeader("X-Capture-Token");
                            if (supplied == null
                                    || !MessageDigest.isEqual(
                                            supplied.getBytes(StandardCharsets.UTF_8),
                                            token.getBytes(StandardCharsets.UTF_8))) {
                                context.response().setStatusCode(403).end("接入凭证无效，请重启采集器并重进小游戏");
                                return;
                            }
                            if (context.body().length() > 24 * 1024 * 1024) {
                                context.response().setStatusCode(413).end("实时批次过大");
                                return;
                            }
                            http.worker(
                                    context,
                                    () ->
                                            service.ingest(
                                                    mapper.readTree(context.body().asString())));
                        });
        router.get("/api/live/config")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                mapper.createObjectNode()
                                                        .put(
                                                                "origin",
                                                                "http://127.0.0.1:" + boundPort)
                                                        .put("token", token)
                                                        .put("schemaVersion", 1)));
        router.get("/api/live/events")
                .handler(
                        context -> {
                            HttpServerResponse response = context.response();
                            if (subscribers.size() >= AnalyzerLimits.MAX_SSE_SUBSCRIBERS) {
                                response.setStatusCode(429).end("订阅连接过多");
                                return;
                            }
                            response.setChunked(true)
                                    .putHeader("Content-Type", "text/event-stream; charset=utf-8")
                                    .putHeader("Cache-Control", "no-cache")
                                    .putHeader("X-Accel-Buffering", "no");
                            subscribers.add(response);
                            response.closeHandler(ignored -> subscribers.remove(response));
                            response.exceptionHandler(ignored -> subscribers.remove(response));
                            vertx.executeBlocking(service::status, false)
                                    .onSuccess(
                                            status -> {
                                                if (!response.closed() && !response.ended()) {
                                                    response.write(
                                                            "event: status\ndata: "
                                                                    + status
                                                                    + "\n\n");
                                                }
                                            })
                                    .onFailure(failure -> response.end());
                        });
    }

    public void bind(int port) {
        boundPort = port;
    }

    private void broadcast(String event) {
        for (HttpServerResponse response : Set.copyOf(subscribers)) {
            if (response.ended() || response.closed() || response.writeQueueFull()) {
                subscribers.remove(response);
                if (!response.closed()) {
                    response.end();
                }
                continue;
            }
            response.write(event);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            service.close();
        } finally {
            vertx.cancelTimer(keepAlive);
            vertx.runOnContext(
                    ignored -> {
                        for (HttpServerResponse response : Set.copyOf(subscribers)) {
                            response.end();
                        }
                        subscribers.clear();
                    });
        }
    }
}
