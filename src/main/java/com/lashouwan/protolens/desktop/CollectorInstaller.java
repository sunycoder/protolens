package com.lashouwan.protolens.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 缺少随包采集器时自动准备固定版本；校验官方归档后只提取程序，不安装证书或修改代理。 */
public final class CollectorInstaller {
    private static final long MAX_EXECUTABLE_BYTES = 128L * 1024 * 1024;

    /** 只由随包资源提供官方地址和摘要；构造注入用于受控本机 HTTP 测试，不向网页开放下载 URL。 */
    private final URI archive;

    private final String sha256;

    CollectorInstaller(URI archive, String sha256) {
        this.archive = archive;
        this.sha256 = sha256;
    }

    public static CollectorInstaller forCurrentPlatform() throws IOException {
        Properties properties = new Properties();
        try (InputStream input =
                CollectorInstaller.class.getResourceAsStream(
                        "/collector-distribution.properties")) {
            if (input == null) {
                throw new IOException("缺少采集器分发描述");
            }
            properties.load(input);
        }
        String platform = RuntimePlatform.current().id();
        String url = properties.getProperty(platform + ".url");
        String digest = properties.getProperty(platform + ".sha256");
        if (url == null || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IOException("当前平台没有自动采集器分发：" + platform + "，请使用含本机采集器的安装版");
        }
        return new CollectorInstaller(URI.create(url), digest);
    }

    /** 在启动线程或构建进程执行；临时文件与目标同目录，失败保留已有文件，不解压其他 ZIP 路径。 */
    public Path prepare(Path executable) throws Exception {
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        Path directory = Files.createDirectories(executable.toAbsolutePath().getParent());
        try (FileChannel channel =
                        FileChannel.open(
                                directory.resolve("collector-install.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(executable)) {
                return executable;
            }
            Path downloaded = Files.createTempFile(directory, "collector-download-", ".zip");
            Path extracted = Files.createTempFile(directory, "collector-verified-", ".exe");
            try {
                download(downloaded);
                if (!sha256.equalsIgnoreCase(digest(downloaded))) {
                    throw new IOException("采集器归档 SHA-256 校验失败，未安装任何程序");
                }
                extractExecutable(downloaded, executable.getFileName().toString(), extracted);
                try {
                    Files.move(extracted, executable, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(extracted, executable);
                }
                return executable;
            } finally {
                Files.deleteIfExists(downloaded);
                Files.deleteIfExists(extracted);
            }
        }
    }

    private void download(Path destination) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(archive).timeout(Duration.ofMinutes(3)).GET().build();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(destination));
            try {
                HttpResponse<Path> response = future.get(180, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    throw new IOException("采集器下载失败，HTTP " + response.statusCode());
                }
                if (Files.size(destination) > 256L * 1024 * 1024) {
                    throw new IOException("采集器归档超过大小限制");
                }
            } catch (Exception failure) {
                future.cancel(true);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String reason =
                        failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage();
                throw new IOException("采集器自动下载失败：" + reason, failure);
            }
        }
    }

    private static String digest(Path archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void extractExecutable(Path archive, String name, Path target)
            throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().equals(name)) {
                    continue;
                }
                long total = 0;
                byte[] buffer = new byte[64 * 1024];
                try (OutputStream output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_EXECUTABLE_BYTES) {
                            throw new IOException("采集器程序超过大小限制");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                try (InputStream executable = Files.newInputStream(target)) {
                    if (executable.read() != 'M' || executable.read() != 'Z') {
                        throw new IOException("归档中的采集器不是 Windows 可执行程序");
                    }
                }
                return;
            }
        }
        throw new IOException("官方归档缺少采集器：" + name);
    }

    /** Gradle 打包前与 IDE 启动共用同一准备实现，避免重复维护下载脚本。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("需要提供采集器目标路径");
        }
        Path target = Path.of(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(target)) {
            forCurrentPlatform().prepare(target);
        }
        System.out.println("采集器已就绪：" + target);
    }
}
