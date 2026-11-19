package com.lashouwan.protolens.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.constant.NetworkConstants;
import com.lashouwan.protolens.enums.FrontendMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/** 启动时冻结的强类型配置；文件低于显式启动参数，本机页面偏好不能覆盖端口。 */
public final class AnalyzerConfig {
    private static final Set<String> KEYS =
            Set.of(
                    "serverPort",
                    "frontendPort",
                    "collectorPort",
                    "frontendMode",
                    "openBrowser",
                    "dataDirectory",
                    "competitorPath",
                    "protocolFile",
                    "protocolAutoLoad",
                    "autoStartCollector",
                    "autoSystemProxy",
                    "captureHosts",
                    "capturePathPrefixes",
                    "maxMessages",
                    "maxCacheMiB");

    /** 后端 HTTP 端口；0 只用于静态模式的系统分配端口。 */
    private final int serverPort;

    /** 源码开发页面端口；静态页面与后端共用端口。 */
    private final int frontendPort;

    /** 采集代理监听端口，与实际系统代理设置保持一致。 */
    private final int collectorPort;

    /** auto 根据源码/发行包选择 dev/static，显式配置可固定模式。 */
    private final FrontendMode frontendMode;

    /** 就绪后打开默认浏览器，默认 true。 */
    private final boolean openBrowser;

    /** 源码相对模块、发行包相对配置目录的数据位置，允许绝对路径。 */
    private final String dataDirectory;

    /** 首次启动默认协议来源，可为目录或 thrift_data.js 文件；空值表示手动配置。 */
    private final String competitorPath;

    /** 多候选时的相对文件路径；空值只接受唯一候选。 */
    private final String protocolFile;

    /** 是否在启动时加载默认或用户保存的协议来源。 */
    private final boolean protocolAutoLoad;

    /** 是否自动启动本机采集器；不自动修改系统代理或安装证书。 */
    private final boolean autoStartCollector;

    /** 采集器就绪后自动接管 Windows 系统代理；已有代理必须在页面确认，默认 true。 */
    private final boolean autoSystemProxy;

    /** 代理解密域名和 WebSocket 消息路径，启动时冻结，由统一配置提供。 */
    private final CaptureTargets captureTargets;

    /** 每条实时连接保留的消息条数，超过后淘汰最旧消息。 */
    private final int maxMessages;

    /** 每条实时连接序列化消息容量预算，单位 MiB，不等于精确 JVM 堆占用。 */
    private final int maxCacheMiB;

    private AnalyzerConfig(JsonNode input) {
        serverPort = integer(input, "serverPort", 18766, 0, NetworkConstants.MAX_PORT);
        frontendPort = integer(input, "frontendPort", 5174, 1, NetworkConstants.MAX_PORT);
        collectorPort = integer(input, "collectorPort", 18888, 1, NetworkConstants.MAX_PORT);
        frontendMode = FrontendMode.fromCode(text(input, "frontendMode", FrontendMode.AUTO.code()));
        if (serverPort == collectorPort || frontendPort == collectorPort) {
            throw new IllegalArgumentException("采集器端口必须与后端、开发页面端口不同");
        }
        openBrowser = bool(input, "openBrowser", true);
        dataDirectory = text(input, "dataDirectory", "data");
        if (dataDirectory.isBlank()) {
            throw new IllegalArgumentException("dataDirectory 不能为空");
        }
        competitorPath = text(input, "competitorPath", "D:/work/20261008/slgProject");
        protocolFile = text(input, "protocolFile", "");
        protocolAutoLoad = bool(input, "protocolAutoLoad", true);
        autoStartCollector = bool(input, "autoStartCollector", true);
        autoSystemProxy = bool(input, "autoSystemProxy", true);
        captureTargets = CaptureTargets.from(input);
        maxMessages =
                integer(
                        input,
                        "maxMessages",
                        AnalyzerLimits.DEFAULT_CACHE_MESSAGES,
                        AnalyzerLimits.MIN_CACHE_MESSAGES,
                        AnalyzerLimits.MAX_CACHE_MESSAGES);
        maxCacheMiB =
                integer(
                        input,
                        "maxCacheMiB",
                        AnalyzerLimits.DEFAULT_CACHE_MIB,
                        AnalyzerLimits.MIN_CACHE_MIB,
                        AnalyzerLimits.MAX_CACHE_MIB);
    }

    /** 缺少默认文件时使用类中默认值；显式指定文件的存在性由启动解析器检查。 */
    public static AnalyzerConfig load(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input =
                Files.isRegularFile(file)
                        ? mapper.readTree(file.toFile())
                        : mapper.createObjectNode();
        return from(input);
    }

    /** 同一校验入口供启动加载和页面配置保存使用，不执行端口绑定或启动进程。 */
    static AnalyzerConfig from(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("analyzer.json 须为 JSON 对象");
        }
        Iterator<String> fields = input.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("未知启动配置项：" + key);
            }
        }
        return new AnalyzerConfig(input);
    }

    /** 页面兼容字段用于展示与采集器诊断；不暴露可变的配置内部对象。 */
    public ObjectNode snapshot(ObjectMapper mapper) {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("serverPort", serverPort)
                        .put("frontendPort", frontendPort)
                        .put("collectorPort", collectorPort)
                        .put("frontendMode", frontendMode.code())
                        .put("openBrowser", openBrowser)
                        .put("dataDirectory", dataDirectory)
                        .put("competitorPath", competitorPath)
                        .put("protocolFile", protocolFile)
                        .put("protocolAutoLoad", protocolAutoLoad)
                        .put("autoStartCollector", autoStartCollector)
                        .put("autoSystemProxy", autoSystemProxy)
                        .put("maxMessages", maxMessages)
                        .put("maxCacheMiB", maxCacheMiB);
        result.set("captureHosts", mapper.valueToTree(captureTargets.hosts()));
        result.set("capturePathPrefixes", mapper.valueToTree(captureTargets.pathPrefixes()));
        return result;
    }

    private static int integer(JsonNode input, String key, int fallback, int minimum, int maximum) {
        if (!input.has(key)) {
            return fallback;
        }
        JsonNode value = input.get(key);
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < minimum
                || value.asInt() > maximum) {
            throw new IllegalArgumentException(key + " 必须为 " + minimum + "～" + maximum + " 的整数");
        }
        return value.asInt();
    }

    private static boolean bool(JsonNode input, String key, boolean fallback) {
        if (!input.has(key)) {
            return fallback;
        }
        if (!input.get(key).isBoolean()) {
            throw new IllegalArgumentException(key + " 必须为布尔值");
        }
        return input.get(key).asBoolean();
    }

    private static String text(JsonNode input, String key, String fallback) {
        if (!input.has(key)) {
            return fallback;
        }
        if (!input.get(key).isTextual()) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return input.get(key).asText().trim();
    }

    public int serverPort() {
        return serverPort;
    }

    public int frontendPort() {
        return frontendPort;
    }

    public int collectorPort() {
        return collectorPort;
    }

    public String frontendMode() {
        return frontendMode.code();
    }

    public boolean openBrowser() {
        return openBrowser;
    }

    public String dataDirectory() {
        return dataDirectory;
    }

    public String competitorPath() {
        return competitorPath;
    }

    public String protocolFile() {
        return protocolFile;
    }

    public boolean protocolAutoLoad() {
        return protocolAutoLoad;
    }

    public boolean autoStartCollector() {
        return autoStartCollector;
    }

    public boolean autoSystemProxy() {
        return autoSystemProxy;
    }

    public CaptureTargets captureTargets() {
        return captureTargets;
    }

    public int maxMessages() {
        return maxMessages;
    }

    public int maxCacheMiB() {
        return maxCacheMiB;
    }
}
