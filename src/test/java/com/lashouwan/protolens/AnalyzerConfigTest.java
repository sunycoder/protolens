package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.config.AnalyzerConfig;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 启动配置的优先级、严格类型和本机偏好边界；不依赖真实端口或竞品目录。 */
class AnalyzerConfigTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private Path module() throws Exception {
        Files.createDirectories(directory.resolve("config"));
        Files.createDirectories(directory.resolve("web"));
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'protolens'");
        Files.writeString(directory.resolve("web/vite.config.ts"), "export default {}");
        return directory;
    }

    @Test
    void testFileThenExplicitOverrides_ShouldControlAllPortsAndDataDirectory() throws Exception {
        Path root = module();
        Files.writeString(
                root.resolve("config/analyzer.json"),
                """
                {"serverPort":19111,"frontendPort":19112,"collectorPort":19113,
                 "dataDirectory":"storage","openBrowser":false,"competitorPath":""}
                """);
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(new String[0], root, root, new Properties());
        assertEquals(19111, options.port());
        assertEquals("http://127.0.0.1:19112", options.devOrigin());
        assertEquals(19113, options.config().collectorPort());
        assertEquals(root.resolve("storage"), options.dataDirectory());
        assertFalse(options.openBrowser());
        Properties properties = new Properties();
        properties.setProperty("analyzer.port", "19121");
        assertEquals(
                19121, AnalyzerLaunchOptions.resolve(new String[0], root, root, properties).port());
        assertEquals(
                19131,
                AnalyzerLaunchOptions.resolve(
                                new String[] {"--port", "19131"}, root, root, properties)
                        .port());
    }

    @Test
    void testBadConfiguration_ShouldRejectUnknownKeysTypesAndPortCollision() throws Exception {
        Path root = module();
        Path file = root.resolve("config/analyzer.json");
        for (String invalid :
                new String[] {
                    "{\"serverPort\":\"18766\"}",
                    "{\"openBrowser\":\"false\"}",
                    "{\"serverProt\":18766}",
                    "{\"maxCacheMiB\":1.5}",
                    "{\"serverPort\":18888}"
                }) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> AnalyzerConfig.load(file));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnalyzerLaunchOptions.resolve(
                                new String[] {"--config", "missing.json"},
                                root,
                                root,
                                new Properties()));
    }

    @Test
    void testLegacyPreferences_ShouldKeepUserSourceButNeverOverrideStartupPorts() throws Exception {
        Path root = module();
        Files.writeString(
                root.resolve("config/analyzer.json"),
                "{\"serverPort\":19111,\"competitorPath\":\"default\"}");
        Files.createDirectories(root.resolve("data"));
        Files.writeString(
                root.resolve("data/settings.json"),
                """
                {"serverPort":19999,"collectorPort":19998,"autoStartCollector":false,
                 "competitorPath":"chosen","maxMessages":2000}
                """);
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(new String[0], root, root, new Properties());
        AnalyzerSettingsStore store = new AnalyzerSettingsStore(options, mapper);
        assertEquals(19111, options.port());
        assertEquals(18888, store.snapshot().path("collectorPort").asInt());
        assertTrue(store.snapshot().path("autoStartCollector").asBoolean());
        assertEquals("chosen", store.snapshot().path("competitorPath").asText());
        store.update(mapper.createObjectNode().put("maxMessages", 3000));
        var persisted = mapper.readTree(root.resolve("data/settings.json").toFile());
        assertFalse(persisted.has("serverPort"));
        assertFalse(persisted.has("collectorPort"));
    }
}
