package com.lashouwan.protolens.desktop.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 当前工作台运行期间复用原生网络授权会话；密码及 AuthorizationRef 均不进入 Java、磁盘或网页。 只经父子进程私有管道发送代理快照，不开放本机监听端口；断开管道即释放系统授权。
 */
final class MacProxyAuthorizationSession implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessFactory processFactory;
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    @FunctionalInterface
    interface ProcessFactory {
        Process start() throws Exception;
    }

    MacProxyAuthorizationSession() {
        this(MacProxyAuthorizationSession::startProcess);
    }

    MacProxyAuthorizationSession(ProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    synchronized void write(MacProxySettings expected, MacProxySettings target) throws Exception {
        if (!expected.service().equals(target.service())
                || !expected.device().equals(target.device())) {
            throw new IllegalArgumentException("不能跨网络服务修改代理快照");
        }
        start();
        var request = mapper.createObjectNode();
        request.set("expected", mapper.valueToTree(expected));
        request.set("target", mapper.valueToTree(target));
        String json = mapper.writeValueAsString(request);
        if (json.getBytes(StandardCharsets.UTF_8).length > 131071) {
            throw new IOException("代理快照过大，未发送系统授权请求");
        }
        CompletableFuture<String> response = null;
        try {
            writer.write(json);
            writer.newLine();
            writer.flush();
            BufferedReader activeReader = reader;
            response =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    String line = activeReader.readLine();
                                    if (line == null || line.length() > 8192)
                                        throw new IOException("代理授权会话已断开");
                                    return line;
                                } catch (IOException failure) {
                                    throw new java.io.UncheckedIOException(failure);
                                }
                            });
            var result = mapper.readTree(response.get(120, TimeUnit.SECONDS));
            if (!result.path("ok").isBoolean()) throw new IOException("代理授权会话响应无效");
            if (!result.path("ok").asBoolean()) {
                // 明确拒绝不自动重放；会话仍保留，用户重试时由系统判断是否重新授权。
                throw new ProxyRejectedException(result.path("message").asText("macOS 代理操作未完成"));
            }
        } catch (ProxyRejectedException failure) {
            throw failure;
        } catch (Exception failure) {
            // 超时或断连时结果可能未知，上层恢复租约负责核对，禁止自动重发本次操作。
            close();
            if (response != null) response.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("macOS 授权会话中断或超时，请刷新代理状态后重试；恢复备份已保留", failure);
        }
    }

    private void start() throws Exception {
        if (process != null && process.isAlive()) return;
        close();
        process = processFactory.start();
        reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static Process startProcess() throws Exception {
        Path code =
                Path.of(
                        MacProxyAuthorizationSession.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path executable;
        if (Files.isDirectory(code)) {
            executable =
                    com.lashouwan.protolens.config.AnalyzerLaunchOptions.findModule(
                                    code, Path.of(""))
                            .resolve("build/macos-native/ProtoLensProxySession");
        } else {
            Path root = code.getParent();
            if (root.getFileName().toString().equals("lib")) root = root.getParent();
            executable = root.resolve("runtime-tools/ProtoLensProxySession");
        }
        if (!Files.isExecutable(executable)) {
            throw new IOException(
                    "缺少 macOS 原生代理授权组件；源码请先执行 gradlew prepareMacProxySession，安装版请更新完整应用");
        }
        return new ProcessBuilder(executable.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** 在恢复代理之后关闭，先关闭 stdin 让原生组件主动销毁授权引用，再回收子进程。 */
    @Override
    public synchronized void close() {
        Process current = process;
        process = null;
        try {
            if (writer != null) writer.close();
            if (current != null && !current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (current != null) current.destroyForcibly();
        } catch (IOException failure) {
            if (current != null) current.destroyForcibly();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            writer = null;
            reader = null;
        }
    }

    private static final class ProxyRejectedException extends IOException {
        private ProxyRejectedException(String message) {
            super(message);
        }
    }
}
