package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolCatalogService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 配置覆盖、真实 AST 提取及版本隔离测试；竞品样例为临时目录中的静态文本。 */
class AnalyzerSettingsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private AnalyzerLaunchOptions options() throws Exception {
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        return AnalyzerLaunchOptions.resolve(
                new String[] {"--data-dir", directory.resolve("data").toString()},
                module,
                module,
                new Properties());
    }

    private ProtocolCatalog catalog() {
        ObjectNode document = mapper.createObjectNode().put("schemaVersion", 1);
        document.putObject("messages");
        document.putObject("definitions");
        return new ProtocolCatalog(document);
    }

    private Path client(String name) throws Exception {
        Path source = directory.resolve("client/script/subpackages/scripts___4/thrift_data.js");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "function sample(e) { e('thriftData', {2364:{1:['groupId',8]}}); e('messageData',"
                        + " [['"
                        + name
                        + "',2364]]); e('structData', []); }");
        return directory.resolve("client");
    }

    @Test
    void testReload_ShouldMapNewMessagesButKeepExistingCaptureDictionary() throws Exception {
        AnalyzerSettingsStore settings = new AnalyzerSettingsStore(options(), mapper);
        ProtocolCatalog catalog = catalog();
        ProtocolCatalogService protocols =
                new ProtocolCatalogService(options(), settings, catalog, mapper);
        Path client = client("CgSample");
        protocols.reload(client.toString(), "");
        assertEquals("CgSample", catalog.name(2364));
        CaptureStore store = new CaptureStore(directory.resolve("captures"), mapper, catalog);
        String old = store.createLive("a", "https://example.test/websocket", "");
        store.stopLive(old, "STOPPED");
        client("CgChanged");
        protocols.reload(client.toString(), "");
        assertEquals("CgChanged", catalog.name(2364));
        assertEquals(
                "CgSample",
                store.read(old)
                        .path("dictionary")
                        .path("messages")
                        .path("2364")
                        .path("name")
                        .asText());
        assertEquals(
                client.toString(),
                new AnalyzerSettingsStore(options(), mapper)
                        .snapshot()
                        .path("competitorPath")
                        .asText());
        Files.writeString(
                client.resolve("script/subpackages/scripts___4/thrift_data.js"),
                "function invalid(e){e('thriftData', evil());}");
        assertThrows(Exception.class, () -> protocols.reload(client.toString(), ""));
        assertEquals("CgChanged", catalog.name(2364));
    }

    @Test
    void testMultipleFiles_ShouldRequireSelectionAndReuseRelativeChoiceOnStartup()
            throws Exception {
        AnalyzerSettingsStore settings = new AnalyzerSettingsStore(options(), mapper);
        ProtocolCatalog catalog = catalog();
        ProtocolCatalogService protocols =
                new ProtocolCatalogService(options(), settings, catalog, mapper);
        Path root = client("CgVersionFour");
        Path original = root.resolve("script/subpackages/scripts___4/thrift_data.js");
        Path second = root.resolve("script/subpackages/scripts___7/thrift_data.js");
        Files.createDirectories(second.getParent());
        Files.writeString(
                second, Files.readString(original).replace("CgVersionFour", "CgVersionSeven"));
        assertEquals(2, protocols.discover(root.toString()).path("candidates").size());
        assertThrows(IllegalArgumentException.class, () -> protocols.reload(root.toString(), ""));
        assertFalse(catalog.mapped(2364));
        protocols.reload(root.toString(), "script/subpackages/scripts___7/thrift_data.js");
        assertEquals("CgVersionSeven", catalog.name(2364));
        assertEquals(
                "script/subpackages/scripts___7/thrift_data.js",
                settings.snapshot().path("protocolFile").asText());
        ProtocolCatalog restarted = catalog();
        new ProtocolCatalogService(
                        options(), new AnalyzerSettingsStore(options(), mapper), restarted, mapper)
                .initialize();
        assertEquals("CgVersionSeven", restarted.name(2364));
        assertThrows(
                IllegalArgumentException.class,
                () -> protocols.reload(root.toString(), "../other.js"));
        protocols.reload(original.toString(), "");
        assertEquals("CgVersionFour", catalog.name(2364));
        assertEquals(original.toString(), settings.snapshot().path("competitorPath").asText());
        assertEquals("", settings.snapshot().path("protocolFile").asText());
        assertEquals(original.toString(), settings.snapshot().path("loadedProtocolFile").asText());
        assertEquals("thrift_data.js", catalog.summary(mapper).path("sourceFile").asText());
    }

    @Test
    void testConfiguredDefault_ShouldLoadWithoutSavedPreferences() throws Exception {
        Path source = client("CgDefault");
        Path file = directory.resolve("default-config.json");
        mapper.writeValue(
                file.toFile(), mapper.createObjectNode().put("competitorPath", source.toString()));
        Path module = AnalyzerLaunchOptions.findModule(Path.of(""), Path.of(""));
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--config",
                            file.toString(),
                            "--data-dir",
                            directory.resolve("first-run").toString()
                        },
                        module,
                        module,
                        new Properties());
        AnalyzerSettingsStore settings = new AnalyzerSettingsStore(options, mapper);
        ProtocolCatalog catalog = catalog();
        ProtocolCatalogService protocols =
                new ProtocolCatalogService(options, settings, catalog, mapper);
        protocols.initialize();
        assertEquals("CgDefault", catalog.name(2364));
        assertEquals("", protocols.lastError());
    }

    @Test
    void testSettings_ShouldPersistBudgetsAndRejectMutationDuringCapture() throws Exception {
        AnalyzerSettingsStore settings = new AnalyzerSettingsStore(options(), mapper);
        ProtocolCatalog catalog = catalog();
        ProtocolCatalogService protocols =
                new ProtocolCatalogService(options(), settings, catalog, mapper);
        CaptureStore store = new CaptureStore(directory.resolve("captures"), mapper, catalog);
        try (LiveCaptureService service =
                new LiveCaptureService(store, catalog, mapper, ignored -> {})) {
            service.configure(
                    settings,
                    mapper.createObjectNode().put("maxMessages", 1500).put("maxCacheMiB", 4));
            assertEquals(
                    1500,
                    new AnalyzerSettingsStore(options(), mapper)
                            .snapshot()
                            .path("maxMessages")
                            .asInt());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.configure(
                                    settings, mapper.createObjectNode().put("maxMessages", 0)));
            service.start();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.configure(
                                    settings, mapper.createObjectNode().put("maxMessages", 2000)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.reloadCatalog(protocols, client("CgSample").toString(), ""));
            assertEquals(1500, settings.snapshot().path("maxMessages").asInt());
        }
    }
}
