package com.lashouwan.protolens.desktop;

import com.lashouwan.protolens.enums.OperatingSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** 分析工作台的原生运行平台；集中解释数据目录和工具身份，不进入游戏业务或协议映射。 */
public record RuntimePlatform(String os, String architecture) {
    /** 只接受经过构建脚本覆盖的平台组合，防止将未知系统当成Linux后继续启动错误二进制。 */
    public RuntimePlatform {
        if (!((OperatingSystem.WINDOWS.code().equals(os) && "x64".equals(architecture))
                || ((OperatingSystem.MACOS.code().equals(os)
                                || OperatingSystem.LINUX.code().equals(os))
                        && ("x64".equals(architecture) || "arm64".equals(architecture))))) {
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
            os = OperatingSystem.MACOS.code();
        } else if (name.startsWith("windows")) {
            os = OperatingSystem.WINDOWS.code();
        } else if (name.contains("linux")) {
            os = OperatingSystem.LINUX.code();
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

    /** 内部使用枚举比较，外部 os 字段仍保留发行包与历史配置的字符串合同。 */
    public OperatingSystem operatingSystem() {
        return OperatingSystem.fromCode(os);
    }

    public String id() {
        return os + "-" + architecture;
    }

    public String executableSuffix() {
        return OperatingSystem.WINDOWS.code().equals(os) ? ".exe" : "";
    }

    /** 默认数据目录不位于安装包内；Linux只接受绝对XDG目录，否则回退用户目录。 */
    public Path dataDirectory(Path userHome, Map<String, String> environment) {
        Path root =
                switch (operatingSystem()) {
                    case WINDOWS -> {
                        String local = environment.get("LOCALAPPDATA");
                        yield (local == null || local.isBlank() ? userHome : Path.of(local));
                    }
                    case MACOS -> userHome.resolve("Library/Application Support");
                    case LINUX -> {
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
                };
        // 更名前已存在的数据目录继续复用，保留抓包、证书及代理恢复凭据。
        Path legacy = root.resolve("SanguoPacketAnalyzer");
        return Files.isDirectory(legacy) ? legacy : root.resolve("ProtoLens");
    }
}
