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
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 本机启动检查：同一服务提供前端和 API，动态端口对采集器可见，失败或关闭释放资源。 */
class ApplicationStartupTest {
    @TempDir Path directory;

    @Test
    void testStaticApplication_ShouldServeUiAndApiThenCloseIdempotently() throws Exception {
        Files.createDirectories(directory.resolve("web"));
        Files.writeString(
                directory.resolve("web/index.html"), "<!doctype html><title>本机启动检查</title>");
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'protolens'");
        Files.writeString(directory.resolve("web/vite.config.ts"), "export default {}");
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(
                directory.resolve("config/analyzer.json"), "{\"protocolAutoLoad\":false}");
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--mode",
                            "static",
                            "--port",
                            "0",
                            "--web-root",
                            "web",
                            "--open-browser",
                            "false"
                        },
                        directory,
                        directory,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        try (HttpClient client = HttpClient.newHttpClient()) {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            String origin = "http://127.0.0.1:" + port;
            var page =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("本机启动检查"));
            var health =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/api/health"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertTrue(new ObjectMapper().readTree(health.body()).path("ready").asBoolean());
            var config =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/api/live/config"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(
                    origin, new ObjectMapper().readTree(config.body()).path("origin").asText());
            var first = app.close();
            assertSame(first, app.close());
            first.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            try {
                app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
