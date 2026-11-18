package com.lashouwan.protolens.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.util.Map;
import java.util.concurrent.Callable;

/** HTTP 适配公共逻辑；同步文件、协议扫描和状态锁等待统一放 worker，不在事件循环执行。 */
public final class AnalyzerHttpSupport {
    private final Vertx vertx;
    private final ObjectMapper mapper;

    public AnalyzerHttpSupport(Vertx vertx, ObjectMapper mapper) {
        this.vertx = vertx;
        this.mapper = mapper;
    }

    public void worker(RoutingContext context, Callable<Object> action) {
        vertx.executeBlocking(action, false)
                .onSuccess(value -> reply(context, value))
                .onFailure(error -> fail(context, error));
    }

    public JsonNode body(RoutingContext context) throws Exception {
        if (context.body().isEmpty()) {
            return mapper.createObjectNode();
        }
        JsonNode input = mapper.readTree(context.body().buffer().getBytes());
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("请求体须为 JSON 对象");
        }
        return input;
    }

    public void reply(RoutingContext context, Object value) {
        if (context.response().ended() || context.response().closed()) {
            return;
        }
        try {
            context.response()
                    .putHeader("Content-Type", "application/json; charset=utf-8")
                    .end(mapper.writeValueAsString(value));
        } catch (Exception failure) {
            context.response().setStatusCode(500).end("{\"error\":\"响应序列化失败\"}");
        }
    }

    public void fail(RoutingContext context, Throwable failure) {
        if (context.response().ended() || context.response().closed()) {
            return;
        }
        String message =
                failure == null || failure.getMessage() == null ? "请求失败" : failure.getMessage();
        context.response().setStatusCode(400);
        reply(context, Map.of("error", message));
    }
}
