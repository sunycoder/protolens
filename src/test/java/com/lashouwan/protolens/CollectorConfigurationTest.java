package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.config.AnalyzerConfig;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.CaptureTargets;
import com.lashouwan.protolens.config.CollectorConfigStore;

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
import java.util.regex.Pattern;

/** 可配置采集目标的域名边界、文件保存和 HTTP 展示；修改待生效配置不污染当前进程。 */
class CollectorConfigurationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void targetMatchingEscapesDomainAndKeepsBoundariesAndPathCase() throws Exception {
        var input =
                mapper.readTree(
                        "{\"captureHosts\":[\"Game.Example.COM\",\"127.0.0.1\"],\"capturePathPrefixes\":[\"/custom\"]}");
        CaptureTargets targets = CaptureTargets.from(input);
        Pattern pattern = Pattern.compile(targets.allowHostsPattern());
        assertTrue(pattern.matcher("game.example.com:443").find());
        assertTrue(pattern.matcher("wss.GAME.example.com:8443").find());
        assertTrue(pattern.matcher("127.0.0.1:8080").find());
        assertFalse(pattern.matcher("evilgame.example.com:443").find());
        assertFalse(pattern.matcher("game.example.com.evil.test:443").find());
        assertFalse(pattern.matcher("not.127.0.0.1:8080").find());
        assertFalse(pattern.matcher("gameXexampleXcom:443").find());
        assertEquals("/custom", targets.pathPrefixes().getFirst());
        for (String invalid :
                new String[] {
                    "{\"captureHosts\":[]}",
                    "{\"captureHosts\":[\"https://game.example.com\"]}",
                    "{\"captureHosts\":[\"game.example.com:443\"]}",
                    "{\"captureHosts\":[\".*\"]}",
                    "{\"captureHosts\":[\"999.0.0.1\"]}",
                    "{\"capturePathPrefixes\":[\"ws\"]}",
                    "{\"capturePathPrefixes\":[\"/ws?token=x\"]}",
                    "{\"capturePathPrefixes\":\"/ws\"}"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CaptureTargets.from(mapper.readTree(invalid)));
        }
    }

    @Test
    void savingTargetsPreservesOtherConfigurationAndSeparatesPendingFromActive() throws Exception {
        var options = options();
        var store = new CollectorConfigStore(options, mapper);
        var update =
                mapper.readTree(
                        "{\"captureHosts\":[\"new.example.com\"],\"capturePathPrefixes\":[\"/new-socket\"]}");
        var saved = store.update(update);
        assertTrue(saved.path("restartRequired").asBoolean());
        assertEquals("bjxuejing.cn", saved.path("activeCaptureHosts").get(0).asText());
        assertEquals("new.example.com", saved.path("captureHosts").get(0).asText());
        var persisted = mapper.readTree(options.configFile().toFile());
        assertEquals(19111, persisted.path("serverPort").asInt());
        assertFalse(persisted.path("autoStartCollector").asBoolean());
        assertFalse(Files.exists(options.dataDirectory().resolve("settings.json")));
        String before = Files.readString(options.configFile());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.update(
                                mapper.readTree(
                                        "{\"captureHosts\":[],\"capturePathPrefixes\":[\"/\"]}")));
        assertEquals(before, Files.readString(options.configFile()));
        assertEquals(
                "new.example.com",
                AnalyzerConfig.load(options.configFile()).captureTargets().hosts().getFirst());
        var restarted = new CollectorConfigStore(options(), mapper);
        assertFalse(restarted.snapshot().path("restartRequired").asBoolean());
    }

    @Test
    void configurationEndpointUsesSameStoreAndRejectsInvalidRules() throws Exception {
        var options = options();
        Vertx vertx = Vertx.vertx();
        var app = new ProtoLensApplication(vertx, options);
        try (HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/collector/settings");
            assertEquals(
                    200,
                    client.send(
                                    HttpRequest.newBuilder(uri).GET().build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            var request =
                    HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"captureHosts\":[\"next.example.com\"],\"capturePathPrefixes\":[\"/next\"]}"))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(mapper.readTree(response.body()).path("restartRequired").asBoolean());
            var invalid =
                    HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"captureHosts\":[],\"capturePathPrefixes\":[]}"))
                            .build();
            assertEquals(
                    400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(
                    "next.example.com",
                    AnalyzerConfig.load(options.configFile()).captureTargets().hosts().getFirst());
        } finally {
            try {
                app.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private AnalyzerLaunchOptions options() throws Exception {
        Files.createDirectories(directory.resolve("config"));
        Files.createDirectories(directory.resolve("web"));
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'protolens'");
        Files.writeString(directory.resolve("web/vite.config.ts"), "export default {}");
        Path config = directory.resolve("config/analyzer.json");
        if (!Files.exists(config)) {
            Files.writeString(
                    config,
                    "{\"serverPort\":19111,\"protocolAutoLoad\":false,\"autoStartCollector\":false}");
        }
        return AnalyzerLaunchOptions.resolve(
                new String[] {"--mode", "static", "--port", "0"},
                directory,
                directory,
                new Properties());
    }
}
