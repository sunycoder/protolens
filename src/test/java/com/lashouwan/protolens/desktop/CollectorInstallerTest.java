package com.lashouwan.protolens.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 受控 HTTP 下载验证归档摘要、只提取目标程序和失败不发布；测试不依赖外网。 */
class CollectorInstallerTest {
    @TempDir Path directory;

    /** 发布时保留整个应用及 Python 框架；替换后不能只剩一个孤立的启动器。 */
    @Test
    void publishesCompleteMacCollectorBundle() throws Exception {
        Path staged = Files.createDirectories(directory.resolve("verified/mitmproxy.app"));
        Files.createDirectories(staged.resolve("Contents/MacOS"));
        Files.createDirectories(staged.resolve("Contents/Frameworks"));
        Files.writeString(staged.resolve("Contents/MacOS/mitmdump"), "launcher");
        Files.writeString(staged.resolve("Contents/Frameworks/libpython.dylib"), "runtime");
        Path target =
                Files.createDirectories(directory.resolve("installed")).resolve("mitmproxy.app");
        CollectorInstaller.publishMacBundle(staged, target);
        assertEquals("launcher", Files.readString(target.resolve("Contents/MacOS/mitmdump")));
        assertEquals(
                "runtime", Files.readString(target.resolve("Contents/Frameworks/libpython.dylib")));
        assertFalse(Files.exists(staged));
    }

    /** 暂存应用缺失导致替换失败时，旧应用及其依赖必须完整恢复。 */
    @Test
    void restoresPreviousMacBundleIfReplacementFails() throws Exception {
        Path target = Files.createDirectories(directory.resolve("installed/mitmproxy.app"));
        Files.writeString(target.resolve("previous-runtime"), "keep");
        assertThrows(
                IOException.class,
                () -> CollectorInstaller.publishMacBundle(directory.resolve("missing"), target));
        assertEquals("keep", Files.readString(target.resolve("previous-runtime")));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count(), "成功恢复后不残留暂存备份");
        }
    }

    @Test
    void downloadsOnlyOnceAndExtractsOnlyVerifiedExecutable() throws Exception {
        byte[] zip = archive("mitmdump.exe");
        AtomicInteger requests = new AtomicInteger();
        Vertx vertx = Vertx.vertx();
        var server =
                vertx.createHttpServer()
                        .requestHandler(
                                request -> {
                                    requests.incrementAndGet();
                                    request.response().end(Buffer.buffer(zip));
                                })
                        .listen(0, "127.0.0.1")
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
        try {
            var installer =
                    new CollectorInstaller(
                            URI.create("http://127.0.0.1:" + server.actualPort() + "/archive.zip"),
                            digest(zip));
            Path executable = directory.resolve("tools/mitmdump.exe");
            installer.prepare(executable);
            assertArrayEquals(new byte[] {'M', 'Z', 1, 2}, Files.readAllBytes(executable));
            installer.prepare(executable);
            assertEquals(1, requests.get());
            assertFalse(Files.exists(directory.resolve("outside.exe")));
            try (var files = Files.list(executable.getParent())) {
                assertEquals(2, files.count(), "只保留可执行文件和安装锁，不残留归档或临时文件");
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void hashMismatchAndMissingEntryNeverPublishAnExecutable() throws Exception {
        byte[] zip = archive("other.exe");
        Vertx vertx = Vertx.vertx();
        var server =
                vertx.createHttpServer()
                        .requestHandler(request -> request.response().end(Buffer.buffer(zip)))
                        .listen(0, "127.0.0.1")
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
        URI uri = URI.create("http://127.0.0.1:" + server.actualPort() + "/archive.zip");
        Path executable = directory.resolve("mitmdump.exe");
        try {
            assertThrows(
                    IOException.class,
                    () -> new CollectorInstaller(uri, "0".repeat(64)).prepare(executable));
            assertFalse(Files.exists(executable));
            assertThrows(
                    IOException.class,
                    () -> new CollectorInstaller(uri, digest(zip)).prepare(executable));
            assertFalse(Files.exists(executable));
            try (var files = Files.list(directory)) {
                assertEquals(1, files.count());
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static String digest(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] archive(String name) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../outside.exe"));
            zip.write(new byte[] {'M', 'Z'});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            zip.write(new byte[] {'M', 'Z', 1, 2});
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
