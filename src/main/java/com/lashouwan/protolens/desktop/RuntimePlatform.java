package com.lashouwan.protolens.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** 分析工作台的原生运行平台；集中解释数据目录和工具身份，不进入游戏业务或协议映射。 */
public record RuntimePlatform(String os, String architecture) {
    /** 只接受经过构建脚本覆盖的平台组合，防止将未知系统当成Linux后继续启动错误二进制。 */
    public RuntimePlatform {
        if (!((os.equals("windows") && architecture.equals("x64"))
                || ((os.equals("macos") || os.equals("linux"))
                        && (architecture.equals("x64") || architecture.equals("arm64"))))) {
            throw new IllegalArgumentException("不支持的运行平台: " + os + "/" + architecture);
        }
    }

    public static RuntimePlatform current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /** JDK报告的系统/架构映射为构建目录身份；Apple Silicon与Intel分别匹配各自原生工具。 */
    public static RuntimePlatform detect(String osName, String archName) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.contains("mac") || name.contains("darwin")) {
            os = "macos";
        } else if (name.startsWith("windows")) {
            os = "windows";
        } else if (name.contains("linux")) {
            os = "linux";
        } else {
            throw new IllegalArgumentException("不支持的操作系统: " + osName);
        }
        String architecture =
                switch (archName.toLowerCase(Locale.ROOT)) {
                    case "amd64", "x86_64", "x64" -> "x64";
                    case "aarch64", "arm64" -> "arm64";
                    default -> throw new IllegalArgumentException("不支持的CPU架构: " + archName);
                };
        return new RuntimePlatform(os, architecture);
    }

    public String id() {
        return os + "-" + architecture;
    }

    public String executableSuffix() {
        return os.equals("windows") ? ".exe" : "";
    }

    /** 默认数据目录不位于安装包内；Linux只接受绝对XDG目录，否则回退用户目录。 */
    public Path dataDirectory(Path userHome, Map<String, String> environment) {
        Path root =
                switch (os) {
                    case "windows" -> {
                        String local = environment.get("LOCALAPPDATA");
                        yield (local == null || local.isBlank() ? userHome : Path.of(local));
                    }
                    case "macos" -> userHome.resolve("Library/Application Support");
                    case "linux" -> {
                        String xdg = environment.get("XDG_DATA_HOME");
                        Path linuxRoot =
                                xdg == null || xdg.isBlank()
                                        ? userHome.resolve(".local/share")
                                        : Path.of(xdg);
                        if (!linuxRoot.isAbsolute()) {
                            linuxRoot = userHome.resolve(".local/share");
                        }
                        yield linuxRoot;
                    }
                    default -> throw new IllegalStateException("平台未校验");
                };
        // 更名前已存在的数据目录继续复用，保留抓包、证书及代理恢复凭据。
        Path legacy = root.resolve("SanguoPacketAnalyzer");
        return Files.isDirectory(legacy) ? legacy : root.resolve("ProtoLens");
    }
}
