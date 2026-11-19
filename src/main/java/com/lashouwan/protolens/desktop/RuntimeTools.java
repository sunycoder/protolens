package com.lashouwan.protolens.desktop;

import com.lashouwan.protolens.config.AnalyzerLaunchOptions;

import java.nio.file.Files;
import java.nio.file.Path;

/** 集中定位原生工具：安装版优先使用随包程序，源码版保留已安装的本机工具。 */
public final class RuntimeTools {
    private RuntimeTools() {}

    public static String node(AnalyzerLaunchOptions options) {
        Path bundled =
                options.moduleRoot()
                        .resolve(
                                "runtime-tools/node"
                                        + RuntimePlatform.current().executableSuffix());
        return Files.isRegularFile(bundled) ? bundled.toString() : "node";
    }

    public static Path collector(AnalyzerLaunchOptions options) {
        if (RuntimePlatform.current().os().equals("macos")) {
            Path relative = Path.of("mitmproxy.app/Contents/MacOS/mitmdump");
            Path bundled = options.moduleRoot().resolve("runtime-tools").resolve(relative);
            return Files.isRegularFile(bundled)
                    ? bundled
                    : options.dataDirectory().resolve("mitmproxy").resolve(relative);
        }
        String name = "mitmdump" + RuntimePlatform.current().executableSuffix();
        Path bundled = options.moduleRoot().resolve("runtime-tools").resolve(name);
        return Files.isRegularFile(bundled)
                ? bundled
                : options.dataDirectory().resolve("mitmproxy").resolve(name);
    }
}
