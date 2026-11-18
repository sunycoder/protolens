package com.lashouwan.protolens;

import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.desktop.DesktopLauncher;
import com.lashouwan.protolens.desktop.DevelopmentFrontend;
import com.lashouwan.protolens.desktop.InstanceCoordinator;
import com.lashouwan.protolens.http.DesktopController;

import io.vertx.core.Vertx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** IDEA 与 Gradle 共用启动入口：配置 → 应用绑定 → 前端就绪 → 自动打开页面。 */
public final class ProtoLensMain {
    private static Logger logger;

    private ProtoLensMain() {}

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Exception failure) {
            if (logger == null) {
                logger = LoggerFactory.getLogger(ProtoLensMain.class);
            }
            logger.error("ProtoLens 协议分析工作台启动失败 reason:{}", failure.getMessage(), failure);
            DesktopLauncher.showStartupFailure(
                    "ProtoLens 协议分析工作台启动失败："
                            + failure.getMessage()
                            + "\n日志目录："
                            + System.getProperty("analyzer.logDirectory", "data/logs"),
                    () -> System.exit(1));
        }
    }

    private static void launch(String[] args) throws Exception {
        Path location =
                Path.of(
                        ProtoLensMain.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        AnalyzerLaunchOptions options =
                AnalyzerLaunchOptions.resolve(args, location, Path.of(""), System.getProperties());
        Files.createDirectories(options.dataDirectory());
        System.setProperty(
                "analyzer.logDirectory", options.dataDirectory().resolve("logs").toString());
        logger = LoggerFactory.getLogger(ProtoLensMain.class);
        if (!options.development()
                && !Files.isRegularFile(options.webRoot().resolve("index.html"))) {
            throw new IllegalArgumentException(
                    "静态前端尚未构建，请执行 gradlew -p protolens webBuild，或使用默认源码开发模式");
        }
        InstanceCoordinator instance = new InstanceCoordinator(options.dataDirectory());
        if (!instance.isOwner()) {
            try {
                if (!instance.openExisting()) {
                    throw new IllegalStateException(instance.getOpenFailureReason());
                }
            } finally {
                instance.close();
            }
            return;
        }
        DesktopLauncher desktop = new DesktopLauncher();
        AtomicReference<String> page = new AtomicReference<>("");
        Vertx vertx;
        try {
            vertx = Vertx.vertx();
        } catch (Exception failure) {
            instance.close();
            throw failure;
        }
        DevelopmentFrontend frontend = new DevelopmentFrontend();
        ProtoLensApplication app =
                new ProtoLensApplication(
                        vertx,
                        options,
                        new DesktopController(
                                instance.getControl(),
                                () -> {
                                    String address = page.get();
                                    if (!address.isEmpty()) {
                                        desktop.open(address);
                                    }
                                }));
        AtomicBoolean stopping = new AtomicBoolean();
        Runnable close =
                () -> {
                    if (!stopping.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        frontend.close();
                    } catch (RuntimeException failure) {
                        logger.warn("前端进程收尾失败 reason:{}", failure.getMessage());
                    }
                    try {
                        // 在启动/退出线程依次等待；不把 Vert.x 关闭接回即将销毁的事件循环上下文。
                        try {
                            app.close()
                                    .toCompletionStage()
                                    .toCompletableFuture()
                                    .get(30, TimeUnit.SECONDS);
                        } finally {
                            vertx.close()
                                    .toCompletionStage()
                                    .toCompletableFuture()
                                    .get(30, TimeUnit.SECONDS);
                        }
                    } catch (Exception failure) {
                        logger.warn("应用收尾未完成 reason:{}", failure.getMessage());
                    } finally {
                        desktop.close();
                        try {
                            instance.close();
                        } catch (Exception failure) {
                            logger.warn("单实例锁释放失败 reason:{}", failure.getMessage());
                        }
                    }
                };
        Thread hook = new Thread(close, "protolens-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            int port =
                    app.start().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            try {
                app.startCollector();
            } catch (Exception collectorFailure) {
                // 可选采集器故障保留页面与离线导入入口，页面显示诊断；不让整个工作台闪退。
                logger.warn("自动准备采集器失败，工作台仍可使用 reason:{}", collectorFailure.getMessage());
            }
            try {
                app.initializeSystemProxy();
            } catch (Exception proxyFailure) {
                logger.warn("自动代理未完成，保留页面供用户处理 reason:{}", proxyFailure.getMessage());
            }
            if (options.development()) {
                frontend.start(options);
            }
            String browserAddress =
                    options.development() ? options.devOrigin() : "http://127.0.0.1:" + port;
            logger.info(
                    "ProtoLens 协议分析工作台就绪 browserAddress:{} backendPort:{} dataDirectory:{} mode:{}",
                    browserAddress,
                    port,
                    options.dataDirectory(),
                    options.development() ? "Vite热更新" : "静态资源");
            logger.info(
                    "运行地址 configFile:{} backendAddress:{} browserAddress:{} collectorProxy:{}"
                            + " protocolDefault:{}",
                    options.configFile(),
                    "http://127.0.0.1:" + port,
                    browserAddress,
                    "127.0.0.1:" + options.config().collectorPort(),
                    options.config().competitorPath());
            page.set(browserAddress);
            instance.publish(port);
            desktop.install(
                    browserAddress, () -> desktop.open(browserAddress), () -> System.exit(0));
            if (options.openBrowser()) {
                desktop.open(browserAddress);
            }
        } catch (Exception failure) {
            close.run();
            throw failure;
        }
    }
}
