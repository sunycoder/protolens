package com.lashouwan.protolens.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.LiveCaptureService;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.config.CollectorConfigStore;
import com.lashouwan.protolens.constant.NetworkConstants;
import com.lashouwan.protolens.desktop.AnalyzerVersion;
import com.lashouwan.protolens.desktop.CollectorProcess;
import com.lashouwan.protolens.desktop.ReleaseAssets;
import com.lashouwan.protolens.desktop.RuntimePlatform;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolCatalogService;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 服务健康、实际启动地址及页面偏好接口；配置源和生效值一起展示，端口不由页面偏好覆盖。 */
public final class SystemController {
    private final AnalyzerHttpSupport http;
    private final AnalyzerLaunchOptions options;
    private final AnalyzerSettingsStore settings;
    private final ProtocolCatalog catalog;
    private final ProtocolCatalogService protocols;
    private final LiveCaptureService live;
    private final CollectorProcess collector;
    private final ObjectMapper mapper;
    private final CollectorConfigStore collectorConfig;
    private volatile int port;

    public SystemController(
            AnalyzerHttpSupport http,
            AnalyzerLaunchOptions options,
            AnalyzerSettingsStore settings,
            ProtocolCatalog catalog,
            ProtocolCatalogService protocols,
            LiveCaptureService live,
            CollectorProcess collector,
            ObjectMapper mapper) {
        this.http = http;
        this.options = options;
        this.settings = settings;
        this.catalog = catalog;
        this.protocols = protocols;
        this.live = live;
        this.collector = collector;
        this.mapper = mapper;
        this.collectorConfig = new CollectorConfigStore(options, mapper);
    }

    public void bind(int port) {
        this.port = port;
    }

    public void register(Router router) {
        router.get("/api/health")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                mapper.createObjectNode()
                                                        .put("ready", true)
                                                        .put("apiVersion", 2)
                                                        .put("activityWindow", true)
                                                        .put("analysisTools", true)
                                                        .set("catalog", catalog.summary(mapper))));
        router.get("/api/settings")
                .handler(context -> http.worker(context, this::settingsSnapshot));
        router.get("/api/update").handler(context -> http.worker(context, this::updateSnapshot));
        router.get("/api/collector")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () ->
                                                collector
                                                        .status(mapper)
                                                        .put(
                                                                "platform",
                                                                RuntimePlatform.current().os())
                                                        .put(
                                                                "proxyHost",
                                                                NetworkConstants.LOOPBACK_HOST)
                                                        .put(
                                                                "proxyPort",
                                                                options.config().collectorPort())
                                                        .put(
                                                                "certificateAvailable",
                                                                Files.isRegularFile(
                                                                        certificateFile()))
                                                        .put(
                                                                "certificateFile",
                                                                certificateFile().toString())));
        router.get("/api/collector/certificate")
                .handler(
                        context ->
                                context.vertx()
                                        .executeBlocking(
                                                () -> {
                                                    // 仅开放采集器生成的公开 CA，不接收用户路径，也不暴露同目录的私钥文件。
                                                    Path certificate = certificateFile();
                                                    if (!Files.isRegularFile(certificate)
                                                            || Files.size(certificate)
                                                                    > 64 * 1024) {
                                                        throw new IllegalArgumentException(
                                                                "公开证书尚未生成，请确认采集器已运行后刷新");
                                                    }
                                                    return Files.readAllBytes(certificate);
                                                },
                                                false)
                                        .onSuccess(
                                                bytes ->
                                                        context.response()
                                                                .putHeader(
                                                                        "Content-Type",
                                                                        "application/x-x509-ca-cert")
                                                                .putHeader(
                                                                        "Content-Disposition",
                                                                        "attachment;"
                                                                            + " filename=protolens-ca.crt")
                                                                .putHeader(
                                                                        "Cache-Control", "no-store")
                                                                .end(Buffer.buffer(bytes)))
                                        .onFailure(failure -> http.fail(context, failure)));
        router.get("/api/collector/settings")
                .handler(context -> http.worker(context, collectorConfig::snapshot));
        router.post("/api/collector/settings")
                .handler(
                        context ->
                                http.worker(
                                        context, () -> collectorConfig.update(http.body(context))));
        router.post("/api/settings")
                .handler(
                        context ->
                                http.worker(
                                        context,
                                        () -> {
                                            live.configure(settings, http.body(context));
                                            return settingsSnapshot();
                                        }));
    }

    /** 公开 CA 的固定位置；不提供任意本机文件下载能力。 */
    private Path certificateFile() {
        return options.dataDirectory().resolve("mitmproxy-conf/mitmproxy-ca-cert.cer");
    }

    /** 读取公开 GitHub Release 元数据，按当前系统筛选安装包。 */
    private ObjectNode updateSnapshot() throws Exception {
        RuntimePlatform platform = RuntimePlatform.current();
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "https://api.github.com/repos/sunycoder/protolens/releases/latest"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "ProtoLens")
                        .GET()
                        .build();
        var response =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub 更新查询失败：HTTP " + response.statusCode());
        }
        var release = mapper.readTree(response.body());
        String tag = release.path("tag_name").asText("");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        var result =
                mapper.createObjectNode()
                        .put("currentVersion", AnalyzerVersion.current())
                        .put("latestVersion", version)
                        .put("platform", platform.os())
                        .put("arch", platform.architecture())
                        .put("releaseUrl", release.path("html_url").asText(""));
        var asset = ReleaseAssets.select(release.path("assets"), platform);
        if (asset != null) {
            result.put("downloadUrl", asset.path("browser_download_url").asText(""))
                    .put("fileName", asset.path("name").asText(""));
        }
        result.put("available", isNewer(version, AnalyzerVersion.current()));
        return result;
    }

    private boolean isNewer(String candidate, String current) {
        try {
            String[] a = candidate.split("\\."), b = current.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int left = i < a.length ? Integer.parseInt(a[i]) : 0;
                int right = i < b.length ? Integer.parseInt(b[i]) : 0;
                if (left != right) {
                    return left > right;
                }
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return false;
    }

    private ObjectNode settingsSnapshot() {
        ObjectNode result = settings.snapshot().put("catalogError", protocols.lastError());
        String backend = NetworkConstants.HTTP_LOOPBACK_PREFIX + port;
        result.put("dataDirectory", options.dataDirectory().toString())
                .put("openBrowser", options.openBrowser());
        result.put("serverPort", port)
                .put("backendAddress", backend)
                .put(
                        "frontendPort",
                        options.development() ? URI.create(options.devOrigin()).getPort() : port)
                .put("browserAddress", options.development() ? options.devOrigin() : backend)
                .put(
                        "proxyAddress",
                        NetworkConstants.LOOPBACK_HOST + ":" + options.config().collectorPort())
                .put("configFile", options.configFile().toString())
                .put("configPrecedence", "类默认值 < 配置文件 < JVM 参数 < 命令行参数；页面偏好仅覆盖协议来源和缓存预算");
        return result;
    }
}
