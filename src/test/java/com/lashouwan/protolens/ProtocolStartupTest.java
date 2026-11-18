package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 默认来源缺失仍能进入配置页面，随后手动加载；不使用游戏网络或系统代理。 */
class ProtocolStartupTest {
    @TempDir Path directory;

    @Test
    void testMissingDefaultSource_ShouldKeepPageAvailableAndAllowManualLoad() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        Path config = directory.resolve("analyzer.json");
        mapper.writeValue(
                config.toFile(),
                mapper.createObjectNode()
                        .put("serverPort", 0)
                        .put("frontendMode", "static")
                        .put("competitorPath", directory.resolve("missing-client").toString()));
        Path web = directory.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "<title>设置入口</title>");
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--config",
                            config.toString(),
                            "--data-dir",
                            directory.resolve("data").toString(),
                            "--web-root",
                            web.toString()
                        },
                        module,
                        module,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        try (HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            String origin = "http://127.0.0.1:" + port;
            var settings =
                    mapper.readTree(
                            client.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(origin + "/api/settings"))
                                                    .timeout(Duration.ofSeconds(5))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertEquals(port, settings.path("serverPort").asInt());
            assertEquals(origin, settings.path("browserAddress").asText());
            assertFalse(settings.path("catalogError").asText().isEmpty());
            assertEquals(
                    200,
                    client.send(
                                    HttpRequest.newBuilder(URI.create(origin)).GET().build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            Path source = directory.resolve("client/thrift_data.js");
            Files.createDirectories(source.getParent());
            Files.writeString(
                    source,
                    "function"
                        + " test(e){e('thriftData',{1:{1:['value',8]}});e('messageData',[['CgExample',1]]);e('structData',[]);}");
            String body = mapper.createObjectNode().put("path", source.toString()).toString();
            var loaded =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/api/catalog/reload"))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loaded.statusCode());
            assertEquals(1, mapper.readTree(loaded.body()).path("messageCount").asInt());
        } finally {
            try {
                app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
