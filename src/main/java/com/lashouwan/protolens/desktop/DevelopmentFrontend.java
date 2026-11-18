package com.lashouwan.protolens.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 沿用 debugger 的 Vite 生命周期：按服务身份复用，等待就绪，只清理本次创建的前端进程。 */
public final class DevelopmentFrontend implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DevelopmentFrontend.class);
    private static final String READY_PATH = "/__packet_analyzer_dev__";

    /** 仅启动线程做有限 HTTP 就绪探测；不进入 HTTP 请求或解码执行路径。 */
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();

    private Process process;

    /** 源码目录和代理目标均匹配时才复用已有 Vite，绝不停止用户独立启动的前端。 */
    public void start(AnalyzerLaunchOptions options) throws Exception {
        URI origin = AnalyzerLaunchOptions.validateDevOrigin(options.devOrigin());
        Path web = options.moduleRoot().resolve("web").toRealPath();
        String backend = "http://127.0.0.1:" + options.port();
        if (ready(origin, web, backend)) {
            logger.info("复用前端开发服务 origin:{} source:{}", origin, web);
            return;
        }
        Path vite = web.resolve("node_modules/vite/bin/vite.js");
        if (!Files.isRegularFile(vite)) {
            throw new IOException("缺少前端依赖，请先执行 gradlew -p protolens webInstall");
        }
        Path log = options.dataDirectory().resolve("logs/frontend-dev.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder =
                new ProcessBuilder(
                        "node",
                        vite.toString(),
                        "--host",
                        "127.0.0.1",
                        "--port",
                        Integer.toString(origin.getPort()),
                        "--strictPort");
        builder.directory(web.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("PROTOLENS_BACKEND_ORIGIN", backend);
        try {
            process = builder.start();
            for (int attempt = 0; attempt < 120; attempt++) {
                if (!process.isAlive()) {
                    throw new IOException("Vite 启动失败，请检查端口或日志：" + log);
                }
                if (ready(origin, web, backend)) {
                    logger.info("前端热更新已就绪 origin:{} log:{}", origin, log);
                    return;
                }
                Thread.sleep(200);
            }
            throw new IOException("等待 Vite 就绪超时，日志：" + log);
        } catch (Exception failure) {
            close();
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }
    }

    private boolean ready(URI origin, Path web, String backend) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(origin.resolve(READY_PATH))
                            .timeout(Duration.ofSeconds(1))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode result = new ObjectMapper().readTree(response.body());
            return "protolens-vite".equals(result.path("service").asText())
                    && web.toString().replace('\\', '/').equals(result.path("root").asText())
                    && backend.equals(result.path("backend").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 不操作共享 Node 进程；仅关闭本次启动的进程树。 */
    @Override
    public void close() {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            process = null;
        }
    }
}
