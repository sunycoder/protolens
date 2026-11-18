package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.desktop.InstanceCoordinator;
import com.lashouwan.protolens.desktop.RuntimePlatform;
import com.lashouwan.protolens.desktop.RuntimeTools;
import com.lashouwan.protolens.http.DesktopController;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 安装目录只读资源、用户配置升级、随包工具和单实例唤起的边界验证。 */
class PackagedRuntimeTest {
    @TempDir Path temporary;

    @Test
    void packagedConfigurationSurvivesUpgradeAndResolvesDataOutsideImage() throws Exception {
        Path image = temporary.resolve("program files/protolens");
        Files.createDirectories(image.resolve("config"));
        Path jar = Files.createFile(image.resolve("protolens.jar"));
        Path defaults = image.resolve("config/analyzer.json");
        Files.writeString(defaults, "{\"serverPort\":19001,\"protocolAutoLoad\":false}");
        Properties properties = new Properties();
        Path user = temporary.resolve("user data");
        properties.setProperty("analyzer.userDirectory", user.toString());
        var first = AnalyzerLaunchOptions.resolve(new String[0], jar, temporary, properties);
        assertEquals(19001, first.port());
        assertEquals(user.resolve("data"), first.dataDirectory());
        assertEquals(user.resolve("analyzer.json"), first.configFile());
        assertEquals(image.resolve("web"), first.webRoot());
        assertFalse(first.development());
        assertFalse(Files.exists(image.resolve("data")));
        Files.writeString(defaults, "{\"serverPort\":19002}");
        var upgraded = AnalyzerLaunchOptions.resolve(new String[0], jar, temporary, properties);
        assertEquals(19001, upgraded.port());
        Files.writeString(
                first.configFile(), "{\"serverPort\":19003,\"dataDirectory\":\"custom\"}");
        var edited = AnalyzerLaunchOptions.resolve(new String[0], jar, temporary, properties);
        assertEquals(19003, edited.port());
        assertEquals(user.resolve("custom"), edited.dataDirectory());
        Path bundled = Files.createDirectories(image.resolve("runtime-tools"));
        String suffix = RuntimePlatform.current().executableSuffix();
        Files.createFile(bundled.resolve("node" + suffix));
        Files.createFile(bundled.resolve("mitmdump" + suffix));
        assertEquals(bundled.resolve("node" + suffix).toString(), RuntimeTools.node(edited));
        assertEquals(bundled.resolve("mitmdump" + suffix), RuntimeTools.collector(edited));
    }

    @Test
    void platformUserDirectoriesAreIndependentAndRejectRelativeXdgPaths() {
        Path home = temporary.resolve("home");
        Path local = temporary.resolve("local");
        assertEquals(
                local.resolve("ProtoLens"),
                RuntimePlatform.detect("Windows 11", "amd64")
                        .dataDirectory(home, Map.of("LOCALAPPDATA", local.toString())));
        assertEquals(
                home.resolve("Library/Application Support/ProtoLens"),
                RuntimePlatform.detect("Mac OS X", "aarch64").dataDirectory(home, Map.of()));
        assertEquals(
                home.resolve(".local/share/ProtoLens"),
                RuntimePlatform.detect("Linux", "amd64")
                        .dataDirectory(home, Map.of("XDG_DATA_HOME", "relative")));
        assertThrows(
                IllegalArgumentException.class, () -> RuntimePlatform.detect("FreeBSD", "amd64"));
    }

    @Test
    void existingLegacyDataDirectoryIsReused() throws Exception {
        Path local = Files.createDirectories(temporary.resolve("local"));
        Path legacy = Files.createDirectories(local.resolve("SanguoPacketAnalyzer"));
        assertEquals(
                legacy,
                RuntimePlatform.detect("Windows 11", "amd64")
                        .dataDirectory(temporary, Map.of("LOCALAPPDATA", local.toString())));
    }

    @Test
    void secondInstanceOpensOwnerWithoutReleasingOwnerLockAndRejectsWrongControl()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicInteger opened = new AtomicInteger();
        try (InstanceCoordinator owner = new InstanceCoordinator(temporary)) {
            assertTrue(owner.isOwner());
            Router router = Router.router(vertx);
            new DesktopController(owner.getControl(), opened::incrementAndGet).register(router);
            var server =
                    vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(0, "127.0.0.1")
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            owner.publish(server.actualPort());
            try (InstanceCoordinator second = new InstanceCoordinator(temporary)) {
                assertFalse(second.isOwner());
                assertTrue(second.openExisting());
                assertEquals(1, opened.get());
            }
            assertTrue(Files.exists(temporary.resolve("run/instance.json")));
            try (HttpClient client = HttpClient.newHttpClient()) {
                var request =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + server.actualPort()
                                                        + "/api/desktop/open"))
                                .header("X-Instance-Control", "wrong")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build();
                assertEquals(
                        403,
                        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(1, opened.get());
            }
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(temporary.resolve("run/instance.json")));
        try (InstanceCoordinator replacement = new InstanceCoordinator(temporary)) {
            assertTrue(replacement.isOwner());
        }
    }
}
