package com.lashouwan.protolens.http;

import com.lashouwan.protolens.protocol.ProtocolQueryLimits;
import com.lashouwan.protolens.protocol.ProtocolQueryService;

import io.vertx.ext.web.Router;

/** 协议目录的只读入口；字典范围和版本在服务层统一验证，不开放文件路径参数。 */
public final class ProtocolQueryController {
    private final AnalyzerHttpSupport http;
    private final ProtocolQueryService queries;

    public ProtocolQueryController(AnalyzerHttpSupport http, ProtocolQueryService queries) {
        this.http = http;
        this.queries = queries;
    }

    public void register(Router router) {
        router.get("/api/protocol-query")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                queries.search(
                                                        context.request().getParam("captureId", ""),
                                                        context.request().getParam("q", ""),
                                                        context.request().getParam("scope", "all"),
                                                        context.request().getParam("direction", ""),
                                                        Integer.parseInt(
                                                                context.request()
                                                                        .getParam("offset", "0")),
                                                        Integer.parseInt(
                                                                context.request()
                                                                        .getParam(
                                                                                "limit",
                                                                                Integer.toString(
                                                                                        ProtocolQueryLimits
                                                                                                .PAGE_SIZE))))));
        router.get("/api/protocol-query/definition")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                queries.definition(
                                                        context.request().getParam("captureId", ""),
                                                        context.request().getParam("key", ""),
                                                        context.request().getParam("version", ""),
                                                        Integer.parseInt(
                                                                context.request()
                                                                        .getParam(
                                                                                "fieldOffset",
                                                                                "0")),
                                                        context.request().getParam("matchPath", ""),
                                                        context.request()
                                                                .getParam(
                                                                        "contextCaptureId", ""))));
        router.get("/api/protocol-query/source")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                queries.source(
                                                        context.request().getParam("captureId", ""),
                                                        context.request().getParam("key", ""),
                                                        context.request()
                                                                .getParam("version", ""))));
    }
}
