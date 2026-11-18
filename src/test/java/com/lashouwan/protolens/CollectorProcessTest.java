package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.desktop.CollectorProcess;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 使用隔离端口检查真实便携采集器的自动启动、心跳和关闭；不修改系统代理和证书存储。 */
class CollectorProcessTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testManagedCollector_ShouldStartAndReleaseOnlyOwnedListener() throws Exception {
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        Path installed = module.resolve("data/mitmproxy/mitmdump.exe");
        assumeTrue(Files.isRegularFile(installed), "此环境未安装便携采集器，跳过真实进程验证");
        Files.createDirectories(directory.resolve("mitmproxy"));
        Files.copy(installed, directory.resolve("mitmproxy/mitmdump.exe"));
        int proxyPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            proxyPort = socket.getLocalPort();
        }
        mapper.writeValue(
                directory.resolve("analyzer.json").toFile(),
                mapper.createObjectNode()
                        .put("collectorPort", proxyPort)
                        .put("protocolAutoLoad", false));
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--mode",
                            "static",
                            "--port",
                            "0",
                            "--data-dir",
                            directory.toString(),
                            "--config",
                            directory.resolve("analyzer.json").toString()
                        },
                        module,
                        module,
                        new Properties());
        Vertx vertx = Vertx.vertx();
        ProtoLensApplication app = new ProtoLensApplication(vertx, options);
        try {
            int backend =
                    app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            app.startCollector();
            try (var http =
                    java.net.http.HttpClient.newBuilder()
                            .version(java.net.http.HttpClient.Version.HTTP_1_1)
                            .build()) {
                String origin = "http://127.0.0.1:" + backend;
                var start =
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(origin + "/api/live/start"))
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                                .build();
                assertEquals(
                        200,
                        http.send(start, java.net.http.HttpResponse.BodyHandlers.ofString())
                                .statusCode());
                boolean heartbeat = false;
                for (int attempt = 0; attempt < 30; attempt++) {
                    var request =
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(origin + "/api/live/status"))
                                    .timeout(java.time.Duration.ofSeconds(2))
                                    .GET()
                                    .build();
                    var state =
                            mapper.readTree(
                                    http.send(
                                                    request,
                                                    java.net.http.HttpResponse.BodyHandlers
                                                            .ofString())
                                            .body());
                    if (state.path("acknowledged").asLong() > 0) {
                        heartbeat = true;
                        break;
                    }
                    Thread.sleep(100);
                }
                assertTrue(heartbeat, "真实采集器应向当前动态后端端口发送心跳");
                var stop =
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(origin + "/api/live/stop"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        java.net.http.HttpRequest.BodyPublishers.ofString(
                                                "{\"save\":false}"))
                                .build();
                assertEquals(
                        200,
                        http.send(stop, java.net.http.HttpResponse.BodyHandlers.ofString())
                                .statusCode());
            }
            // 外部监听分支不得取得所有权，也不能在 close 时关闭原采集器。
            try (CollectorProcess external = new CollectorProcess()) {
                external.start(
                        options, mapper.createObjectNode().put("collectorPort", proxyPort), 1);
                assertEquals("EXTERNAL", external.status(mapper).path("state").asText());
            }
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", proxyPort)) {
                assertTrue(socket.isConnected());
            }
        } finally {
            try {
                app.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
        boolean released = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", proxyPort), 100);
            } catch (java.io.IOException expected) {
                released = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(released, "采集器退出后不应继续接受代理连接");
        // Windows 映像/杀毒扫描句柄可能晚于进程终态释放；有限重试实际删除，不掩盖仍运行的子进程。
        Path copiedBinary = directory.resolve("mitmproxy/mitmdump.exe");
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Files.deleteIfExists(copiedBinary);
                break;
            } catch (java.nio.file.AccessDeniedException pending) {
                Thread.sleep(100);
            }
        }
        assertFalse(Files.exists(copiedBinary), "采集器退出后可执行文件应释放");
    }
}
