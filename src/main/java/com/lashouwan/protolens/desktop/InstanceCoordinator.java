package com.lashouwan.protolens.desktop;

import com.lashouwan.protolens.constant.NetworkConstants;

import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** 同一数据目录的单实例协调；文件锁判断存活，短期控制口令只用于请已有实例打开界面。 */
public final class InstanceCoordinator implements AutoCloseable {
    /** 当前数据目录单实例锁的文件通道；close只释放当前进程实际持有的资源。 */
    private final FileChannel channel;

    /** 尝试取得的文件锁；null表示另一实例已持有，不能依据陈旧PID强行终止已有程序。 */
    private final FileLock lock;

    /** 已就绪实例发布的本机端口和控制身份文件，当前实例仅在持锁时删除。 */
    private final Path metadata;

    /** 本实例随机生成的本机唤起身份，只用于请求已运行分析工作台打开页面。 */
    private final String control = UUID.randomUUID().toString();

    /** 唤起失败的可诊断原因，不包含控制口令或游戏凭据。 */
    private String openFailureReason = "已有实例仍在启动，尚未发布本机入口";

    public InstanceCoordinator(Path dataDirectory) throws Exception {
        Path run = Files.createDirectories(dataDirectory.resolve("run"));
        metadata = run.resolve("instance.json");
        channel =
                FileChannel.open(
                        run.resolve("instance.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
            acquired = null;
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
        lock = acquired;
    }

    public boolean isOwner() {
        return lock != null;
    }

    public String getControl() {
        return control;
    }

    public String getOpenFailureReason() {
        return openFailureReason;
    }

    public Path getDataDirectory() {
        return metadata.getParent().getParent();
    }

    /** 持锁实例在监听成功后发布实际端口和控制身份；文件ACL只保护此元数据，不修改安装目录或系统权限。 */
    public void publish(int port) throws Exception {
        Files.writeString(
                metadata,
                new JsonObject().put("port", port).put("control", control).encode(),
                StandardCharsets.UTF_8);
        AclFileAttributeView acl = Files.getFileAttributeView(metadata, AclFileAttributeView.class);
        if (acl != null) {
            AclEntry owner =
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(Files.getOwner(metadata))
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build();
            acl.setAcl(List.of(owner));
        }
    }

    /** 仅由再次启动的短命进程调用，不读游戏凭证，也不根据陈旧PID终止任何进程。 */
    public boolean openExisting() throws Exception {
        // 本机控制入口使用HTTP/1.1。Java默认HTTP/2的h2c升级可能使服务端拿不到预期Host，
        // 从而在来源检查阶段拒绝请求；不能通过放宽Host/Origin校验绕过此兼容问题。
        try (HttpClient client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build()) {
            for (int attempt = 0; attempt < 30; attempt++) {
                if (Files.isRegularFile(metadata)) {
                    try {
                        JsonObject value =
                                new JsonObject(Files.readString(metadata, StandardCharsets.UTF_8));
                        int port = value.getInteger("port", 0);
                        String instanceControl = value.getString("control", "");
                        if (port < 1
                                || port > NetworkConstants.MAX_PORT
                                || instanceControl.isEmpty()) {
                            openFailureReason = "已有实例的本机入口信息不完整";
                        } else {
                            HttpRequest request =
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            NetworkConstants.HTTP_LOOPBACK_PREFIX
                                                                    + port
                                                                    + "/api/desktop/open"))
                                            .timeout(Duration.ofSeconds(2))
                                            .header("X-Instance-Control", instanceControl)
                                            .POST(HttpRequest.BodyPublishers.noBody())
                                            .build();
                            int status =
                                    client.send(request, HttpResponse.BodyHandlers.discarding())
                                            .statusCode();
                            if (status == 200) {
                                return true;
                            }
                            openFailureReason = "已有实例拒绝打开页面，HTTP状态码 " + status;
                            // 已收到明确拒绝，不持续重试同一身份，也不能把拒绝误报成未就绪。
                            if (status == 401 || status == 403 || status == 404) {
                                return false;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    } catch (Exception e) {
                        // 第一个进程可能仍在绑定入口，等待其完成，不清理锁或终止已有进程。
                        openFailureReason = "已有实例的本机入口暂时无法访问（" + e.getClass().getSimpleName() + "）";
                    }
                }
                Thread.sleep(100);
            }
        }
        return false;
    }

    @Override
    public void close() throws Exception {
        if (!channel.isOpen()) {
            return;
        }
        try {
            if (lock != null) {
                Files.deleteIfExists(metadata);
            }
        } finally {
            // 元数据被杀毒软件临时占用时，也必须释放本进程的文件锁和通道。
            try {
                if (lock != null && lock.isValid()) {
                    lock.release();
                }
            } finally {
                channel.close();
            }
        }
    }
}
