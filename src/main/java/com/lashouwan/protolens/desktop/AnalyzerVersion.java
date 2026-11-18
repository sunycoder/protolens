package com.lashouwan.protolens.desktop;

import java.io.IOException;
import java.util.Properties;

/** 从构建资源读取应用版本，供托盘和安装包保持同一产品身份。 */
public final class AnalyzerVersion {
    private AnalyzerVersion() {}

    public static String current() {
        Properties properties = new Properties();
        try (var input =
                AnalyzerVersion.class.getResourceAsStream("/analyzer-version.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            return "unknown";
        }
        return properties.getProperty("version", "unknown");
    }
}
