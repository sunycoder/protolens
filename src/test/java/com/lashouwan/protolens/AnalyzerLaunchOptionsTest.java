package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.lashouwan.protolens.config.AnalyzerLaunchOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 源码入口从工程身份定位配置，不依赖 IDEA 的当前工作目录，也不能误选 debugger。 */
class AnalyzerLaunchOptionsTest {
    @TempDir Path directory;

    private Path module() throws Exception {
        Path module = directory.resolve("repo/protolens");
        Files.createDirectories(module.resolve("web"));
        Files.createDirectories(module.resolve("build/classes/java/main"));
        Files.writeString(module.resolve("settings.gradle"), "rootProject.name = 'protolens'");
        Files.writeString(module.resolve("web/vite.config.ts"), "export default {}");
        return module;
    }

    @Test
    void testSourceLaunch_ShouldUseViteAndModuleDataFromAnyWorkingDirectory() throws Exception {
        Path module = module();
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[0],
                        module.resolve("build/classes/java/main"),
                        directory,
                        new Properties());
        assertTrue(options.development());
        assertTrue(options.openBrowser());
        assertEquals("http://127.0.0.1:5174", options.devOrigin());
        assertEquals(module.resolve("data"), options.dataDirectory());
    }

    @Test
    void testExplicitArguments_ShouldOverrideJvmPropertiesAndAllowStaticEphemeralPort()
            throws Exception {
        Path module = module();
        Properties properties = new Properties();
        properties.setProperty("analyzer.port", "18766");
        var options =
                AnalyzerLaunchOptions.resolve(
                        new String[] {
                            "--mode",
                            "static",
                            "--port",
                            "0",
                            "--open-browser",
                            "false",
                            "--data-dir",
                            "test-data"
                        },
                        module.resolve("build/classes/java/main"),
                        directory,
                        properties);
        assertFalse(options.development());
        assertFalse(options.openBrowser());
        assertEquals(0, options.port());
        assertEquals(module.resolve("test-data"), options.dataDirectory());
    }

    @Test
    void testBadConfiguration_ShouldRejectBeforeOpeningPortsOrBrowser() throws Exception {
        Path module = module();
        Path location = module.resolve("build/classes/java/main");
        for (String[] arguments :
                new String[][] {
                    {"--port", "0"},
                    {"--port", "5174"},
                    {"--mode", "other"},
                    {"--open-browser", "yes"},
                    {"--port", "1", "--port", "2"},
                    {"--unknown", "x"},
                    {"--port"}
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            AnalyzerLaunchOptions.resolve(
                                    arguments, location, directory, new Properties()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> AnalyzerLaunchOptions.validateDevOrigin("https://example.test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnalyzerLaunchOptions.validateDevOrigin("http://127.0.0.1:99999"));
    }

    private Properties packagedProperties() {
        Properties properties = new Properties();
        properties.setProperty("analyzer.userDirectory", directory.resolve("user data").toString());
        return properties;
    }

    @Test
    void testPackagedLaunch_ShouldUseDistributionStaticAssetsWithoutSearchingOtherProjects()
            throws Exception {
        Path jar = directory.resolve("distribution/lib/protolens.jar");
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);
        var options =
                AnalyzerLaunchOptions.resolve(new String[0], jar, directory, packagedProperties());
        assertFalse(options.development());
        assertEquals(directory.resolve("distribution/web"), options.webRoot());
    }
}
