package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.desktop.DevelopmentFrontend;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 使用独立端口验证真实 Vite 启动、代理、复用和进程所有权，不干扰用户正在采集的服务。 */
class DevelopmentFrontendTest {
    @TempDir Path data;

    @Test
    void testDevelopmentFrontend_ShouldProxyAndOnlyStopOwnedProcess() throws Exception {
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        int backendPort;
        int frontendPort;
        try (ServerSocket backend = new ServerSocket(0);
                ServerSocket frontend = new ServerSocket(0)) {
            backendPort = backend.getLocalPort();
            frontendPort = frontend.getLocalPort();
        }
        String origin = "http://127.0.0.1:" + frontendPort;
        java.nio.file.Files.writeString(
                data.resolve("analyzer.json"), "{\"protocolAutoLoad\":false}");
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--port",
                            "" + backendPort,
                            "--dev-origin",
                            origin,
                            "--data-dir",
                            data.toString(),
                            "--open-browser",
                            "false",
                            "--config",
                            data.resolve("analyzer.json").toString()
                        },
                        module,
                        module,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        DevelopmentFrontend owner = new DevelopmentFrontend();
        DevelopmentFrontend reused = new DevelopmentFrontend();
        try (HttpClient client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(java.time.Duration.ofSeconds(3))
                        .build()) {
            app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            owner.start(options);
            reused.start(options);
            reused.close();
            var health =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/api/health"))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"ready\":true"));
            var page =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("/@vite/client"));
            owner.close();
            boolean stopped = false;
            for (int attempt = 0; attempt < 30; attempt++) {
                try (ServerSocket released = new ServerSocket(frontendPort)) {
                    stopped = true;
                    break;
                } catch (java.io.IOException pending) {
                    Thread.sleep(100);
                }
            }
            assertTrue(stopped, "只关闭 owner 后应释放其 Vite 监听端口");
        } finally {
            reused.close();
            owner.close();
            try {
                app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
