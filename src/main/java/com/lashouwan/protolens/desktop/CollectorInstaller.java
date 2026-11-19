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

/** 缺少随包采集器时自动准备固定版本；macOS 保留完整官方应用，不安装证书或修改代理。 */
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
        boolean macBundle =
                RuntimePlatform.current().os().equals("macos")
                        && !archive.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        Path bundle =
                macBundle ? executable.toAbsolutePath().getParent().getParent().getParent() : null;
        if (macBundle
                && (!bundle.getFileName().toString().equals("mitmproxy.app")
                        || !bundle.resolve("Contents/MacOS/mitmdump")
                                .equals(executable.toAbsolutePath()))) {
            throw new IOException("macOS 采集器目标必须保留 mitmproxy.app/Contents/MacOS/mitmdump 结构");
        }
        Path directory =
                Files.createDirectories(
                        macBundle ? bundle.getParent() : executable.toAbsolutePath().getParent());
        try (FileChannel channel =
                        FileChannel.open(
                                directory.resolve("collector-install.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(executable)) {
                return executable;
            }
            Path downloaded = Files.createTempFile(directory, "collector-download-", ".archive");
            Path extracted = Files.createTempDirectory(directory, "collector-verified-");
            try {
                download(downloaded);
                if (!sha256.equalsIgnoreCase(digest(downloaded))) {
                    throw new IOException("采集器归档 SHA-256 校验失败，未安装任何程序");
                }
                extractExecutable(downloaded, executable.getFileName().toString(), extracted);
                if (macBundle) {
                    publishMacBundle(extracted.resolve("mitmproxy.app"), bundle);
                    return executable;
                }
                try {
                    Files.move(
                            extracted.resolve(executable.getFileName()),
                            executable,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(extracted.resolve(executable.getFileName()), executable);
                }
                if (!RuntimePlatform.current().os().equals("windows")) {
                    executable.toFile().setExecutable(true, false);
                }
                return executable;
            } finally {
                Files.deleteIfExists(downloaded);
                deleteTree(extracted);
            }
        }
    }

    /** 发布完整的 macOS 采集器应用，目录移动保留 Python 框架与资源间的符号链接。 新应用替换失败时恢复旧应用；恢复也失败则保留备份并抛出异常，不清理最后可用副本。 */
    static void publishMacBundle(Path staged, Path destination) throws IOException {
        Path backupRoot = Files.createTempDirectory(destination.getParent(), "collector-previous-");
        Path backup = backupRoot.resolve("mitmproxy.app");
        boolean hadPrevious = false;
        boolean published = false;
        try {
            if (Files.isSymbolicLink(destination)) {
                throw new IOException("采集器应用目录不能是外部符号链接");
            }
            if (Files.exists(destination)) {
                Files.move(destination, backup);
                hadPrevious = true;
            }
            Files.move(staged, destination);
            published = true;
        } catch (IOException failure) {
            if (hadPrevious) Files.move(backup, destination);
            throw failure;
        } finally {
            if (published || !Files.exists(backup)) deleteTree(backupRoot);
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

    private void extractExecutable(Path archive, String name, Path target) throws IOException {
        boolean zipArchive =
                this.archive.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        if (!zipArchive && !RuntimePlatform.current().os().equals("windows")) {
            extractTarGz(archive, name, target);
            return;
        }
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().equals(name)) {
                    continue;
                }
                long total = 0;
                byte[] buffer = new byte[64 * 1024];
                Path outputFile = target.resolve(name);
                try (OutputStream output = Files.newOutputStream(outputFile)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_EXECUTABLE_BYTES) {
                            throw new IOException("采集器程序超过大小限制");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                try (InputStream executable = Files.newInputStream(outputFile)) {
                    if (executable.read() != 'M' || executable.read() != 'Z') {
                        throw new IOException("归档中的采集器不是 Windows 可执行程序");
                    }
                }
                return;
            }
        }
        throw new IOException("官方归档缺少采集器：" + name);
    }

    private static void extractTarGz(Path archive, String name, Path target) throws IOException {
        Process process =
                new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString())
                        .redirectErrorStream(true)
                        .start();
        try {
            if (process.waitFor(180, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (var paths = Files.walk(target)) {
                    Path executable =
                            paths.filter(Files::isRegularFile)
                                    .filter(path -> path.getFileName().toString().equals(name))
                                    .findFirst()
                                    .orElseThrow(() -> new IOException("官方归档缺少采集器：" + name));
                    Path output = target.resolve(name);
                    if (RuntimePlatform.current().os().equals("macos")) {
                        Path bundle = executable.getParent().getParent().getParent();
                        if (!bundle.getFileName().toString().equals("mitmproxy.app")
                                || !Files.isDirectory(bundle.resolve("Contents/Frameworks"))
                                || !Files.isDirectory(bundle.resolve("Contents/Resources"))) {
                            throw new IOException("官方归档缺少完整 macOS 采集器应用");
                        }
                        validateNativeExecutable(executable);
                        Path outputBundle = target.resolve("mitmproxy.app");
                        if (!bundle.equals(outputBundle)) Files.move(bundle, outputBundle);
                        return;
                    }
                    Files.move(executable, output, StandardCopyOption.REPLACE_EXISTING);
                    validateNativeExecutable(output);
                    return;
                }
            }
            throw new IOException("解压采集器归档失败");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("解压采集器归档被中断", interrupted);
        }
    }

    private static void validateNativeExecutable(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] magic = input.readNBytes(4);
            boolean valid =
                    RuntimePlatform.current().os().equals("linux")
                            ? magic.length >= 4
                                    && magic[0] == 0x7f
                                    && magic[1] == 'E'
                                    && magic[2] == 'L'
                                    && magic[3] == 'F'
                            : magic.length >= 4
                                    && ((magic[0] == (byte) 0xcf
                                                    && magic[1] == (byte) 0xfa
                                                    && magic[2] == (byte) 0xed
                                                    && magic[3] == (byte) 0xfe)
                                            || (magic[0] == (byte) 0xfe
                                                    && magic[1] == (byte) 0xed
                                                    && magic[2] == (byte) 0xfa
                                                    && magic[3] == (byte) 0xcf));
            if (!valid) throw new IOException("归档中的采集器不是当前平台原生可执行程序");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
        }
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
