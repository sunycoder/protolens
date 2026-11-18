package com.lashouwan.protolens.config;

import com.lashouwan.protolens.desktop.RuntimePlatform;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 参照 debugger 的源码启动默认值：定位本模块、解析覆盖参数，源码默认启用 Vite 热更新。 */
public final class AnalyzerLaunchOptions {
    /** 当前模块目录，不依赖 IDEA 将工作目录设在仓库根还是模块根。 */
    private final Path moduleRoot;

    /** 本机后端端口；静态模式允许 0，由系统分配；开发模式要求固定端口。 */
    private final int port;

    /** 抓包和字典数据目录；源码默认模块 data，安装版默认用户应用目录内 data。 */
    private final Path dataDirectory;

    /** 静态前端构建目录，仅静态模式提供此目录下的文件。 */
    private final Path webRoot;

    /** Vite 本机 origin；静态模式为空字符串。 */
    private final String devOrigin;

    /** 页面就绪后是否自动打开默认浏览器，默认 true。 */
    private final boolean openBrowser;

    /** 已校验的启动配置及其实际文件位置，不包含页面临时运行状态。 */
    private final AnalyzerConfig config;

    private final Path configFile;

    private AnalyzerLaunchOptions(
            Path root,
            int port,
            Path data,
            Path web,
            String origin,
            boolean open,
            AnalyzerConfig config,
            Path configFile) {
        this.moduleRoot = root;
        this.port = port;
        this.dataDirectory = data;
        this.webRoot = web;
        this.devOrigin = origin;
        this.openBrowser = open;
        this.config = config;
        this.configFile = configFile;
    }

    /** 显式命令行优先于 JVM 参数；不写入用户配置或修改 IDE 设置。 */
    public static AnalyzerLaunchOptions resolve(
            String[] args, Path codeLocation, Path workingDirectory, Properties properties)
            throws IOException {
        Map<String, String> arguments = new HashMap<>();
        Set<String> allowed =
                Set.of(
                        "port",
                        "data-dir",
                        "web-root",
                        "mode",
                        "dev-origin",
                        "open-browser",
                        "config");
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("启动参数采用 --名称 值 格式");
            }
            String name = args[index].substring(2);
            if (!allowed.contains(name) || arguments.putIfAbsent(name, args[index + 1]) != null) {
                throw new IllegalArgumentException("未知或重复启动参数：" + name);
            }
        }
        boolean source = Files.isDirectory(codeLocation);
        Path root =
                source
                        ? findModule(codeLocation, workingDirectory)
                        : distributionRoot(codeLocation);
        Path userRoot = source ? root : packagedUserDirectory(properties);
        String explicitConfig = option(arguments, "config", properties, "analyzer.config", "");
        Path configFile =
                explicitConfig.isBlank()
                        ? (source
                                ? root.resolve("config/analyzer.json")
                                : userRoot.resolve("analyzer.json"))
                        : workingDirectory.resolve(explicitConfig).toAbsolutePath().normalize();
        if (!explicitConfig.isBlank() && !Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("指定的配置文件不存在：" + configFile);
        }
        if (!source && explicitConfig.isBlank() && !Files.exists(configFile)) {
            Files.createDirectories(userRoot);
            Path defaults = root.resolve("config/analyzer.json");
            if (Files.isRegularFile(defaults)) {
                try {
                    // 首次初始化才复制；更新或并发启动均不得覆盖用户端口和协议来源。
                    Files.copy(defaults, configFile);
                } catch (FileAlreadyExistsException ignored) {
                    // 另一启动进程已完成首次初始化，沿用该文件。
                }
            }
        }
        AnalyzerConfig config = AnalyzerConfig.load(configFile);
        int port =
                Integer.parseInt(
                        option(
                                arguments,
                                "port",
                                properties,
                                "analyzer.port",
                                Integer.toString(config.serverPort())));
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("后端端口必须为 0～65535");
        }
        String explicitWeb = option(arguments, "web-root", properties, "analyzer.web", "");
        String defaultMode = config.frontendMode();
        if (defaultMode.equals("auto")) {
            defaultMode = source && explicitWeb.isBlank() ? "dev" : "static";
        }
        String mode = option(arguments, "mode", properties, "analyzer.mode", defaultMode);
        if (!mode.equals("dev") && !mode.equals("static")) {
            throw new IllegalArgumentException("mode 必须是 dev 或 static");
        }
        if (mode.equals("dev") && !explicitWeb.isBlank()) {
            throw new IllegalArgumentException("开发模式不能同时指定静态 web-root");
        }
        String origin =
                mode.equals("dev")
                        ? option(
                                arguments,
                                "dev-origin",
                                properties,
                                "analyzer.devOrigin",
                                "http://127.0.0.1:" + config.frontendPort())
                        : "";
        if (!origin.isBlank()) {
            validateDevOrigin(origin);
            if (port == 0 || port == URI.create(origin).getPort()) {
                throw new IllegalArgumentException("开发模式需固定后端端口，并与 Vite 端口不同");
            }
        } else if (arguments.containsKey("dev-origin")) {
            throw new IllegalArgumentException("静态模式不接受 dev-origin");
        }
        Path data =
                resolvePath(
                        source ? root : configFile.getParent(),
                        option(
                                arguments,
                                "data-dir",
                                properties,
                                "analyzer.data",
                                config.dataDirectory()));
        Path web =
                explicitWeb.isBlank()
                        ? root.resolve(source ? "web/dist" : "web")
                        : resolvePath(root, explicitWeb);
        String open =
                option(
                        arguments,
                        "open-browser",
                        properties,
                        "analyzer.openBrowser",
                        Boolean.toString(config.openBrowser()));
        if (!open.equals("true") && !open.equals("false")) {
            throw new IllegalArgumentException("open-browser 必须为 true 或 false");
        }
        if (port == config.collectorPort()
                || (!origin.isBlank() && URI.create(origin).getPort() == config.collectorPort())) {
            throw new IllegalArgumentException("启动参数覆盖后的后端/页面端口不能与采集器端口相同");
        }
        return new AnalyzerLaunchOptions(
                root, port, data, web, origin, Boolean.parseBoolean(open), config, configFile);
    }

    /** 打包版使用独立的用户应用目录；显式覆盖用于便携隔离运行和自动化验收。 */
    private static Path packagedUserDirectory(Properties properties) {
        String explicit = properties.getProperty("analyzer.userDirectory", "");
        if (!explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        return RuntimePlatform.current()
                .dataDirectory(
                        Path.of(
                                properties.getProperty(
                                        "user.home", System.getProperty("user.home"))),
                        System.getenv());
    }

    private static String option(
            Map<String, String> arguments,
            String key,
            Properties properties,
            String property,
            String fallback) {
        return arguments.getOrDefault(key, properties.getProperty(property, fallback));
    }

    private static Path resolvePath(Path root, String value) {
        return root.resolve(value).toAbsolutePath().normalize();
    }

    private static Path distributionRoot(Path location) {
        Path parent = location.toAbsolutePath().getParent();
        return parent != null && parent.getFileName().toString().equals("lib")
                ? parent.getParent()
                : parent;
    }

    /** 只向上定位当前工程，且检查模块名，不能误选 debugger/web 或其他项目。 */
    public static Path findModule(Path location, Path workingDirectory) throws IOException {
        for (Path start : new Path[] {location, workingDirectory}) {
            Path current = start.toAbsolutePath();
            for (int depth = 0;
                    current != null && depth < 10;
                    depth++, current = current.getParent()) {
                for (Path candidate : new Path[] {current, current.resolve("protolens")}) {
                    Path settings = candidate.resolve("settings.gradle");
                    if (Files.isRegularFile(settings)
                            && Files.isRegularFile(candidate.resolve("web/vite.config.ts"))
                            && Files.readString(settings)
                                    .contains("rootProject.name = 'protolens'")) {
                        return candidate.toRealPath();
                    }
                }
            }
        }
        throw new IOException("未找到 protolens 所属源码目录，请检查工作目录和模块结构");
    }

    public static URI validateDevOrigin(String origin) {
        if (origin == null || !origin.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IllegalArgumentException("Vite 地址必须为 http://127.0.0.1:端口");
        }
        URI uri = URI.create(origin);
        if (uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Vite 端口无效");
        }
        return uri;
    }

    public AnalyzerConfig config() {
        return config;
    }

    public Path configFile() {
        return configFile;
    }

    public Path moduleRoot() {
        return moduleRoot;
    }

    public int port() {
        return port;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path webRoot() {
        return webRoot;
    }

    public String devOrigin() {
        return devOrigin;
    }

    public boolean development() {
        return !devOrigin.isBlank();
    }

    public boolean openBrowser() {
        return openBrowser;
    }
}
