package com.lashouwan.protolens.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.capture.MessageQuery;
import com.lashouwan.protolens.constant.AnalyzerLimits;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.nio.file.Files;
import java.nio.file.Path;

/** 抓包导入、查询和记录操作入口；存储一致性归 CaptureStore，实时状态归 LiveCaptureService。 */
public final class CaptureController {
    private final AnalyzerHttpSupport http;
    private final CaptureStore captures;
    private final LiveCaptureService live;

    public CaptureController(
            AnalyzerHttpSupport http, CaptureStore captures, LiveCaptureService live) {
        this.http = http;
        this.captures = captures;
        this.live = live;
    }

    public void register(Router router) {
        router.get("/api/captures/:id/overview")
                .handler(
                        context ->
                                http.worker(
                                        context, () -> captures.describe(context.pathParam("id"))));
        router.post("/api/captures/:id/snapshot")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            JsonNode input = http.body(context);
                                            return captures.snapshotRange(
                                                    context.pathParam("id"),
                                                    input.path("name").asText(),
                                                    input.path("from").asInt(-1),
                                                    input.path("to").asInt(-1));
                                        }));
        router.get("/api/captures").handler(context -> http.worker(context, captures::list));
        router.post("/api/import/saz")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                captures.importSaz(
                                                        context.body().buffer().getBytes(),
                                                        context.request()
                                                                .getParam("name", "capture.saz"))));
        router.post("/api/import/path")
                .handler(context -> http.worker(context, () -> importPath(context)));
        router.delete("/api/captures/:id")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> live.deleteCapture(context.pathParam("id"))));
        router.post("/api/captures/:id/save")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                live.saveCapture(
                                                        context.pathParam("id"),
                                                        http.body(context).path("name").asText())));
        router.get("/api/captures/:id/messages")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                captures.messages(
                                                        context.pathParam("id"), query(context))));
        router.get("/api/captures/:id/messages/:index")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                captures.detail(
                                                        context.pathParam("id"),
                                                        Integer.parseInt(
                                                                context.pathParam("index")))));
        router.post("/api/captures/:id/marks")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            JsonNode input = http.body(context);
                                            return captures.mark(
                                                    context.pathParam("id"),
                                                    input.path("name").asText(),
                                                    input.path("from").asInt(-1),
                                                    input.path("to").asInt(-1));
                                        }));
        router.get("/api/captures/:id/export")
                .handler(
                        context ->
                                http.worker(context, () -> captures.read(context.pathParam("id"))));
    }

    private Object importPath(RoutingContext context) throws Exception {
        Path path = Path.of(http.body(context).path("path").asText());
        if (!Files.isRegularFile(path) || Files.size(path) > AnalyzerLimits.MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("抓包文件不存在或超过 128 MiB");
        }
        return captures.importSaz(Files.readAllBytes(path), path.getFileName().toString());
    }

    private MessageQuery query(RoutingContext context) {
        boolean tail = Boolean.parseBoolean(context.request().getParam("tail", "false"));
        return new MessageQuery(
                context.request().getParam("q", ""),
                context.request().getParam("direction", ""),
                context.request().getParam("status", ""),
                context.request().getParam("connection", ""),
                Boolean.parseBoolean(context.request().getParam("hideHeartbeat", "true")),
                integer(context, "from", 0),
                integer(context, "to", Integer.MAX_VALUE),
                tail ? -1 : Math.max(0, integer(context, "offset", 0)),
                Math.clamp(
                        integer(context, "limit", AnalyzerLimits.DEFAULT_PAGE_SIZE),
                        1,
                        AnalyzerLimits.MAX_PAGE_SIZE),
                context.request().getParam("order", "asc"),
                context.request().getParam("include", ""),
                context.request().getParam("exclude", ""),
                context.request().getParam("protocolId", ""));
    }

    private int integer(RoutingContext context, String key, int fallback) {
        return Integer.parseInt(context.request().getParam(key, Integer.toString(fallback)));
    }
}
