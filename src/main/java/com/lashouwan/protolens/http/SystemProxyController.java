package com.lashouwan.protolens.http;

import com.lashouwan.protolens.desktop.proxy.SystemProxyManager;

import io.vertx.ext.web.Router;

/** 系统代理仅由页面显式接管/恢复，沿用回环来源校验，不对 MCP 开放写操作。 */
public final class SystemProxyController {
    private final AnalyzerHttpSupport http;
    private final SystemProxyManager proxy;

    public SystemProxyController(AnalyzerHttpSupport http, SystemProxyManager proxy) {
        this.http = http;
        this.proxy = proxy;
    }

    public void register(Router router) {
        router.get("/api/system-proxy").handler(context -> http.worker(context, proxy::status));
        router.post("/api/system-proxy/connect")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            var input = http.body(context);
                                            if (input.has("confirmed")
                                                    && !input.get("confirmed").isBoolean()) {
                                                throw new IllegalArgumentException(
                                                        "confirmed 须为布尔值");
                                            }
                                            return proxy.connect(
                                                    input.path("confirmed").asBoolean(false),
                                                    input.path("confirmationToken").asText());
                                        }));
        router.post("/api/system-proxy/decline")
                .handler(context -> http.worker(context, proxy::decline));
        router.post("/api/system-proxy/restore")
                .handler(context -> http.worker(context, proxy::restore));
    }
}
