package com.lashouwan.protolens.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.CaptureTargets;
import com.lashouwan.protolens.constant.NetworkConstants;
import com.lashouwan.protolens.enums.CollectorState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 管理本次后端创建的 mitmdump；不改系统代理、证书，也不终止外部占用端口的进程。 */
public final class CollectorProcess implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CollectorProcess.class);
    private Process process;
    private CollectorState state = CollectorState.NOT_STARTED;
    private String message = "采集器尚未启动";
    private int port;

    /** close 可在下载期间到达；已关闭实例不得在准备完成后再创建子进程。 */
    private boolean closed;

    /** 安装等待不占进程锁，退出可以立即标记关闭；仅真正创建进程时进入锁内。 */
    public void start(AnalyzerLaunchOptions options, JsonNode settings, int backendPort)
            throws Exception {
        synchronized (this) {
            if (closed || process != null || state == CollectorState.PREPARING) {
                return;
            }
            port = settings.path("collectorPort").asInt(options.config().collectorPort());
            if (!settings.path("autoStartCollector")
                    .asBoolean(options.config().autoStartCollector())) {
                state = CollectorState.DISABLED;
                message = "配置已关闭自动启动采集器";
                return;
            }
            if (listening()) {
                state = CollectorState.EXTERNAL;
                message = "代理端口已由外部进程监听，请确认它连接当前工作台；本次不接管";
                logger.warn("{} port:{}", message, port);
                return;
            }
            state = CollectorState.PREPARING;
            message = "正在准备采集器；首次缺失时自动下载并校验，完成后启动";
        }
        Path binary = RuntimeTools.collector(options);
        Path script = options.moduleRoot().resolve("tools/mitmproxy_collector.py");
        try {
            if (!Files.isRegularFile(script)) {
                throw new IOException("缺少随包采集脚本：" + script);
            }
            if (!Files.isRegularFile(binary)) {
                logger.info("{}", message);
                CollectorInstaller.forCurrentPlatform().prepare(binary);
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                launch(options, binary, script, backendPort);
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (!closed) {
                    state = CollectorState.FAILED;
                    message = "采集器准备或启动失败：" + failure.getMessage() + "；检查网络后重启工作台可自动重试";
                }
            }
            throw failure;
        }
    }

    private void launch(AnalyzerLaunchOptions options, Path binary, Path script, int backendPort)
            throws Exception {
        CaptureTargets targets = options.config().captureTargets();
        Path log = options.dataDirectory().resolve("logs/collector.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder =
                new ProcessBuilder(
                        binary.toString(),
                        "--listen-host",
                        NetworkConstants.LOOPBACK_HOST,
                        "--listen-port",
                        "" + port,
                        "--allow-hosts",
                        targets.allowHostsPattern(),
                        "--set",
                        "confdir=" + options.dataDirectory().resolve("mitmproxy-conf"),
                        "--set",
                        "connection_strategy=lazy",
                        "--set",
                        "flow_detail=0",
                        "-s",
                        script.toString());
        builder.directory(options.moduleRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment()
                .put("PROTOLENS_ORIGIN", NetworkConstants.HTTP_LOOPBACK_PREFIX + backendPort);
        ObjectMapper mapper = new ObjectMapper();
        builder.environment()
                .put("PROTOLENS_CAPTURE_HOSTS", mapper.writeValueAsString(targets.hosts()));
        builder.environment()
                .put(
                        "PROTOLENS_CAPTURE_PATH_PREFIXES",
                        mapper.writeValueAsString(targets.pathPrefixes()));
        try {
            process = builder.start();
            for (int attempt = 0; attempt < 100; attempt++) {
                if (!process.isAlive()) {
                    throw new IOException("采集器启动失败，查看 " + log);
                }
                if (listening()) {
                    state = CollectorState.RUNNING;
                    message = "采集器已启动，代理 127.0.0.1:" + port;
                    logger.info("{}", message);
                    return;
                }
                Thread.sleep(100);
            }
            throw new IOException("等待采集器监听超时，查看 " + log);
        } catch (Exception failure) {
            close();
            state = CollectorState.FAILED;
            message = failure.getMessage();
            throw failure;
        }
    }

    private boolean listening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(NetworkConstants.LOOPBACK_HOST, port), 150);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public synchronized ObjectNode status(ObjectMapper mapper) {
        if (process != null && !process.isAlive()) {
            state = CollectorState.FAILED;
            message = "采集器已退出，请查看 data/logs/collector.log";
        }
        return mapper.createObjectNode()
                .put("state", state.name())
                .put("message", message)
                .put("port", port);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (process == null) {
            return;
        }
        // Windows 便携版有解包启动器和实际代理两个进程；先取得句柄，防止父进程退出后丢失子进程关系。
        List<ProcessHandle> children = process.descendants().toList();
        for (ProcessHandle child : children) {
            child.destroy();
        }
        process.destroy();
        try {
            for (ProcessHandle child : children) {
                if (child.isAlive()) {
                    try {
                        child.onExit().get(3, TimeUnit.SECONDS);
                    } catch (ExecutionException | TimeoutException failure) {
                        child.destroyForcibly();
                    }
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException failure) {
            for (ProcessHandle child : children) {
                child.destroyForcibly();
            }
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        process = null;
        state = CollectorState.STOPPED;
        message = "采集器已停止，请关闭 Windows 手动代理";
    }
}
