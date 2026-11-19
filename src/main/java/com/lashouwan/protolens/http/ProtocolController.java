package com.lashouwan.protolens.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.MessageDirection;
import com.lashouwan.protolens.protocol.GamePacketDecoder;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolCatalogService;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.HexFormat;

/** 协议发现、字典换版和单包解码的 HTTP 入口；不负责子进程和配置文件实现。 */
public final class ProtocolController {
    private final AnalyzerHttpSupport http;
    private final ProtocolCatalog catalog;
    private final ProtocolCatalogService protocols;
    private final LiveCaptureService live;
    private final ObjectMapper mapper;

    public ProtocolController(
            AnalyzerHttpSupport http,
            ProtocolCatalog catalog,
            ProtocolCatalogService protocols,
            LiveCaptureService live,
            ObjectMapper mapper) {
        this.http = http;
        this.catalog = catalog;
        this.protocols = protocols;
        this.live = live;
        this.mapper = mapper;
    }

    public void register(Router router) {
        router.get("/api/catalog")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                catalog.search(
                                                        context.request().getParam("q", ""),
                                                        mapper)));
        router.post("/api/catalog/discover")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                protocols.discover(
                                                        http.body(context).path("path").asText())));
        router.post("/api/catalog/reload")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            JsonNode input = http.body(context);
                                            return live.reloadCatalog(
                                                    protocols,
                                                    input.path("path").asText(),
                                                    input.path("file").asText());
                                        }));
        router.post("/api/decode").handler(context -> http.worker(context, () -> decode(context)));
    }

    private Object decode(RoutingContext context) throws Exception {
        JsonNode input = http.body(context);
        String direction = input.path("direction").asText(MessageDirection.CS.name());
        if (!MessageDirection.CS.name().equals(direction)
                && !MessageDirection.SC.name().equals(direction)) {
            throw new IllegalArgumentException("方向必须是 CS 或 SC");
        }
        String hex = input.path("hex").asText().replaceAll("[\\s:-]", "");
        if (hex.length() > AnalyzerLimits.MAX_MESSAGE_BYTES * 2) {
            throw new IllegalArgumentException("消息过大");
        }
        return new GamePacketDecoder(new ProtocolCatalog(catalog.snapshot()), mapper)
                .decode(HexFormat.of().parseHex(hex), direction);
    }
}
