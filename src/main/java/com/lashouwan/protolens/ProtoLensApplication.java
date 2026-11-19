package com.lashouwan.protolens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.constant.NetworkConstants;
import com.lashouwan.protolens.desktop.CollectorProcess;
import com.lashouwan.protolens.desktop.RuntimePlatform;
import com.lashouwan.protolens.desktop.proxy.MacSystemProxyBackend;
import com.lashouwan.protolens.desktop.proxy.SystemProxyManager;
import com.lashouwan.protolens.desktop.proxy.WindowsSystemProxyBackend;
import com.lashouwan.protolens.enums.CollectorState;
import com.lashouwan.protolens.enums.OperatingSystem;
import com.lashouwan.protolens.http.AnalyzerHttpSupport;
import com.lashouwan.protolens.http.CaptureController;
import com.lashouwan.protolens.http.DesktopController;
import com.lashouwan.protolens.http.LiveCaptureController;
import com.lashouwan.protolens.http.LocalAccessGuard;
import com.lashouwan.protolens.http.ProtocolController;
import com.lashouwan.protolens.http.ProtocolQueryController;
import com.lashouwan.protolens.http.SystemController;
import com.lashouwan.protolens.http.SystemProxyController;
import com.lashouwan.protolens.mcp.AnalyzerMcpController;
import com.lashouwan.protolens.mcp.AnalyzerMcpTools;
import com.lashouwan.protolens.mcp.ClientSourceReader;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolCatalogService;
import com.lashouwan.protolens.protocol.ProtocolQueryService;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.nio.file.Files;
import java.nio.file.Path;

/** 应用组合根：装配各职责组件并管理资源生命周期，不承载具体请求和消息处理。 */
public final class ProtoLensApplication {
    private final Vertx vertx;
    private final AnalyzerLaunchOptions options;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CollectorProcess collector = new CollectorProcess();
    private final DesktopController desktop;
    private final SystemProxyManager systemProxy;
    private HttpServer server;
    private LiveCaptureController live;
    private Future<Void> closeFuture;

    /** 绑定完成后的实际端口；启动线程等 start 完成后才能启动采集器。 */
    private int boundPort;

    public ProtoLensApplication(Vertx vertx, AnalyzerLaunchOptions options) {
        this(vertx, options, null);
    }

    public ProtoLensApplication(
            Vertx vertx, AnalyzerLaunchOptions options, DesktopController desktop) {
        this.vertx = vertx;
        this.options = options;
        this.desktop = desktop;
        Path proxyDirectory =
                RuntimePlatform.current()
                        .dataDirectory(Path.of(System.getProperty("user.home")), System.getenv())
                        .resolve("system-proxy");
        String override = System.getProperty("analyzer.proxyDirectory", "");
        if (!override.isBlank()) {
            proxyDirectory = Path.of(override).toAbsolutePath();
        }
        this.systemProxy =
                new SystemProxyManager(
                        RuntimePlatform.current().operatingSystem() == OperatingSystem.MACOS
                                ? new MacSystemProxyBackend()
                                : new WindowsSystemProxyBackend(
                                        options.moduleRoot()
                                                .resolve("tools/WindowsSystemProxy.ps1")),
                        proxyDirectory,
                        options.config().collectorPort(),
                        () ->
                                CollectorState.RUNNING
                                        .name()
                                        .equals(collector.status(mapper).path("state").asText()));
    }

    /** 后端与采集器就绪后才尝试自动接管；冲突留给页面确认，禁止把网络切到未监听端口。 */
    public void initializeSystemProxy() throws Exception {
        systemProxy.initialize(options.config().autoSystemProxy());
    }

    /** 同步就绪探测仅由启动线程调用，不进入 Vert.x 事件循环。 */
    public void startCollector() throws Exception {
        collector.start(options, options.config().snapshot(mapper), boundPort);
    }

    /** 协议路径无效不阻止配置页面和 HTTP 入口开放，配置本身非法则在绑定前拒绝。 */
    public Future<Integer> start() throws Exception {
        Path data = options.dataDirectory();
        ProtocolCatalog catalog = ProtocolCatalog.load(data.resolve("catalog.json"), mapper);
        AnalyzerSettingsStore settings = new AnalyzerSettingsStore(options, mapper);
        ProtocolCatalogService protocols =
                new ProtocolCatalogService(options, settings, catalog, mapper);
        protocols.initialize();
        CaptureStore captures = new CaptureStore(data.resolve("captures"), mapper, catalog);
        ObjectNode budget = settings.snapshot();
        captures.configureLive(
                budget.path("maxMessages").asInt(),
                budget.path("maxCacheMiB").asLong() * AnalyzerLimits.BYTES_PER_MIB);
        AnalyzerHttpSupport http = new AnalyzerHttpSupport(vertx, mapper);
        live = new LiveCaptureController(vertx, captures, catalog, mapper, http);
        LocalAccessGuard guard = new LocalAccessGuard(options.devOrigin());
        SystemController system =
                new SystemController(
                        http,
                        options,
                        settings,
                        catalog,
                        protocols,
                        live.service(),
                        collector,
                        mapper);
        Router router = Router.router(vertx);
        router.route().handler(guard);
        router.route("/api/*")
                .handler(
                        BodyHandler.create()
                                .setHandleFileUploads(false)
                                .setBodyLimit(AnalyzerLimits.MAX_ARCHIVE_BYTES));
        if (desktop != null) {
            desktop.register(router);
        }
        live.register(router, options.port());
        system.register(router);
        new SystemProxyController(http, systemProxy).register(router);
        new ProtocolController(http, catalog, protocols, live.service(), mapper).register(router);
        new ProtocolQueryController(
                        http,
                        new ProtocolQueryService(
                                catalog,
                                captures,
                                new ClientSourceReader(settings, mapper),
                                mapper))
                .register(router);
        new CaptureController(http, captures, live.service()).register(router);
        AnalyzerMcpController mcp =
                new AnalyzerMcpController(
                        vertx,
                        mapper,
                        new AnalyzerMcpTools(
                                captures, new ClientSourceReader(settings, mapper), mapper),
                        data);
        mcp.register(router, http);
        router.route("/api/*")
                .handler(context -> context.response().setStatusCode(404).end("接口不存在"));
        if (Files.isRegularFile(options.webRoot().resolve("index.html"))) {
            router.route()
                    .handler(
                            StaticHandler.create()
                                    .setAllowRootFileSystemAccess(true)
                                    .setWebRoot(options.webRoot().toString())
                                    .setCachingEnabled(false)
                                    .setDirectoryListing(false)
                                    .setIncludeHidden(false));
        } else {
            router.get("/")
                    .handler(
                            context ->
                                    context.response()
                                            .putHeader("Content-Type", "text/plain; charset=utf-8")
                                            .end("协议分析后端已启动，请访问配置的开发页面。"));
        }
        router.route().failureHandler(context -> http.fail(context, context.failure()));
        server = vertx.createHttpServer().requestHandler(router);
        return server.listen(options.port(), NetworkConstants.LOOPBACK_HOST)
                .map(
                        bound -> {
                            boundPort = bound.actualPort();
                            guard.bind(boundPort);
                            system.bind(boundPort);
                            live.bind(boundPort);
                            mcp.bind(boundPort);
                            return boundPort;
                        });
    }

    /** 幂等关闭；逐项清理，采集器异常不得跳过实时服务，HTTP 最后释放。 */
    public synchronized Future<Void> close() {
        if (closeFuture != null) {
            return closeFuture;
        }
        closeFuture =
                vertx.<Void>executeBlocking(
                                () -> {
                                    try {
                                        systemProxy.close();
                                    } finally {
                                        try {
                                            collector.close();
                                        } finally {
                                            if (live != null) {
                                                live.close();
                                            }
                                        }
                                    }
                                    return null;
                                },
                                false)
                        .eventually(
                                () -> server == null ? Future.succeededFuture() : server.close());
        return closeFuture;
    }
}
