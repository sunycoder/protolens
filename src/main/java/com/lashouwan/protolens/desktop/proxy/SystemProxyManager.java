package com.lashouwan.protolens.desktop.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.NetworkConstants;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 用户级代理租约：已有代理二次确认，接管前记账，退出和崩溃恢复均先比对当前配置。 */
public final class SystemProxyManager implements AutoCloseable {
    private final SystemProxyBackend backend;

    /** 全用户唯一的租约目录，不跟随某份抓包数据目录变化，防止多工作台互相覆盖代理。 */
    private final Path directory;

    private final int port;
    private final BooleanSupplier collectorReady;
    private final ObjectMapper mapper = new ObjectMapper();
    private FileChannel channel;
    private FileLock lease;
    private ProxySettings original;
    private ProxySettings applied;
    private boolean automatic;
    private boolean autoPrompt;
    private boolean closed;
    private String message = "未接管系统代理";

    public SystemProxyManager(
            SystemProxyBackend backend, Path directory, int port, BooleanSupplier collectorReady) {
        this.backend = backend;
        this.directory = directory;
        this.port = port;
        this.collectorReady = collectorReady;
    }

    /** 仅在启动线程执行；无现有配置才自动启用，有配置交由页面确认，不弹出隐藏的后台授权。 */
    public synchronized ObjectNode initialize(boolean automatic) throws Exception {
        this.automatic = automatic;
        if (!backend.supported() || !automatic) {
            return status();
        }
        if (backend.requiresAuthorization()) {
            message = "首次接管按 macOS 提示授权；本次运行复用网络授权，系统撤销或授权失效时需重新授权";
            return status();
        }
        recoverAbandoned();
        if (!collectorReady.getAsBoolean()) {
            message = "采集器未就绪，未修改系统代理";
            return status();
        }
        ObjectNode result = connect(false, "");
        autoPrompt = result.path("confirmationRequired").asBoolean();
        return status();
    }

    public synchronized ObjectNode status() throws Exception {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("supported", backend.supported())
                        .put("managed", lease != null && applied != null)
                        .put("autoPrompt", autoPrompt)
                        .put("automatic", automatic)
                        .put("target", NetworkConstants.LOOPBACK_HOST + ":" + port)
                        .put("message", message)
                        .put("requiresAuthorization", backend.requiresAuthorization())
                        .put("vpnNotice", "仅管理系统 HTTP/HTTPS 代理，无法可靠识别或控制 VPN 的 TUN 模式");
        if (!backend.supported()) {
            return result.put("message", "自动接管支持 Windows 和 macOS，其他系统请手动配置代理");
        }
        ProxySettings statusSnapshot = applied;
        if (statusSnapshot == null
                && backend.requiresAuthorization()
                && Files.isRegularFile(stateFile())) {
            // 状态查询只读恢复文件，不取得租约、不弹授权；离线或切网后仍显示原服务的恢复入口。
            statusSnapshot =
                    mapper.treeToValue(
                            mapper.readTree(stateFile().toFile()).get("applied"),
                            ProxySettings.class);
        }
        ProxySettings current = backend.readFor(statusSnapshot);
        result.put("networkNotice", backend.notice(statusSnapshot));
        boolean managed = lease != null && applied != null && applied.equals(current);
        return result.put("managed", managed)
                .put("current", current.description())
                .put("existingIsTarget", !managed && current.pointsTo(port))
                .put("confirmationRequired", !managed && current.hasProxyConfiguration())
                .put("confirmationToken", fingerprint(current))
                .put("canRestore", applied != null || Files.isRegularFile(stateFile()));
    }

    /** 确认绑定当前完整代理快照；用户在弹窗期间切换 VPN 时必须重新确认，不能沿用旧批准。 */
    public synchronized ObjectNode connect(boolean confirmed, String expected) throws Exception {
        if (closed) {
            throw new IllegalStateException("工作台正在退出，不能接管代理");
        }
        if (!backend.supported()) {
            throw new IllegalArgumentException("当前系统不支持自动代理");
        }
        if (!collectorReady.getAsBoolean()) {
            throw new IllegalArgumentException("请等待采集器就绪后再接管，避免游戏断网");
        }
        ProxySettings current = backend.readFor(applied);
        if (lease != null && current.equals(applied)) {
            return status();
        }
        if (applied != null) {
            if (applied.mac() != null) {
                throw new IOException("原网络的代理租约尚未恢复，请先恢复原代理再接管");
            }
            forgetLease();
        }
        if (current.hasProxyConfiguration()
                && (!confirmed || !fingerprint(current).equals(expected))) {
            message = "检测到已有代理配置，请确认是否临时切换到采集代理";
            return status();
        }
        // 先验证平台能力（例如认证代理不可安全备份），拒绝时不持锁、不创建恢复文件。
        ProxySettings nextOriginal = backend.recoveryOriginal(port, current);
        ProxySettings nextApplied = backend.local(port, current);
        if (!acquire()) {
            throw new IOException("另一工作台正在管理系统代理，请先在该工作台恢复代理");
        }
        if (Files.isRegularFile(stateFile())) {
            releaseLock();
            throw new IOException("存在上次运行的代理备份，请先点击恢复原代理，再尝试接管");
        }
        // 持锁后再次读取，防止排队期间设置变化；外部 VPN 不遵循本文件锁，仍需快照核验。
        ProxySettings rechecked = backend.read();
        if (!rechecked.equals(current)) {
            releaseLock();
            message = "系统代理已变化，请重新确认当前配置";
            return status();
        }
        // 已有手动代理正指向本工具时，页面单独说明并确认：退出关闭该遗留手动代理。
        // 不伪造未知的更早 VPN 配置，也不把将要关闭的采集端口恢复为活动代理。
        original = nextOriginal;
        applied = nextApplied;
        try {
            saveLease();
            backend.compareAndWrite(current, applied);
            if (!backend.readFor(applied).equals(applied)) {
                throw new IOException("系统代理写入后校验不一致");
            }
            autoPrompt = false;
            message = "系统代理已接管，退出工作台时自动恢复原配置";
            return status();
        } catch (Exception failure) {
            // 写入结果不明确时只在当前值仍为本工具的值时回滚；保留恢复文件供后续重试。
            try {
                restore();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    public synchronized ObjectNode decline() throws Exception {
        autoPrompt = false;
        if (applied == null) {
            message = "已保留原代理；需要抓包时可点击接管系统代理";
        }
        return status();
    }

    /** 停止接管不关闭采集器；外部程序改过代理时保留新值，绝不覆盖其他软件的接管结果。 */
    public synchronized ObjectNode restore() throws Exception {
        if (!backend.supported()) {
            return status();
        }
        if (applied == null) {
            if (!Files.isRegularFile(stateFile())) {
                return status();
            }
            if (!acquire()) {
                throw new IOException("系统代理正由另一工作台管理，无法恢复");
            }
            readLease();
        }
        ProxySettings current = backend.readFor(applied);
        if (current.equals(applied)) {
            backend.compareAndWrite(current, original);
            if (!backend.readFor(original).equals(original)) {
                throw new IOException("恢复系统代理后的校验失败，已保留恢复备份");
            }
            message = "已恢复接管前的系统代理配置";
        } else if (applied.mac() != null && !current.equals(original)) {
            // macOS 多项 networksetup 调用可能只完成一部分。不能将部分失败当成外部接管后丢弃备份。
            // 也不盲目覆盖第三方新值；保留证据，由用户核对并恢复后再次清理租约。
            throw new IOException("网络代理已变化或仅部分写入，未覆盖当前配置，已保留恢复备份；请核对系统网络设置后重试");
        } else {
            message = "系统代理已被其他程序修改，已保留其当前配置";
        }
        forgetLease();
        autoPrompt = false;
        return status();
    }

    private void recoverAbandoned() throws Exception {
        if (!Files.isRegularFile(stateFile())) {
            return;
        }
        if (!acquire()) {
            message = "系统代理由另一工作台管理";
            return;
        }
        try {
            readLease();
            restore();
        } catch (Exception failure) {
            releaseLock();
            throw failure;
        }
    }

    private Path stateFile() {
        return directory.resolve("proxy-lease.json");
    }

    private boolean acquire() throws IOException {
        if (lease != null) {
            return true;
        }
        Files.createDirectories(directory);
        channel =
                FileChannel.open(
                        directory.resolve("proxy-owner.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            lease = channel.tryLock();
        } catch (OverlappingFileLockException busy) {
            lease = null;
        }
        if (lease == null) {
            channel.close();
            channel = null;
            return false;
        }
        return true;
    }

    private void saveLease() throws Exception {
        Path temporary = Files.createTempFile(directory, "proxy-lease-", ".tmp");
        try {
            var data = mapper.createObjectNode().put("schemaVersion", 1);
            data.set("original", mapper.valueToTree(original));
            data.set("applied", mapper.valueToTree(applied));
            mapper.writeValue(temporary.toFile(), data);
            // PAC URL 可能含私密参数；只允许当前用户读取本恢复文件，不更改其他目录或系统 ACL。
            AclFileAttributeView acl =
                    Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (acl != null) {
                acl.setAcl(
                        List.of(
                                AclEntry.newBuilder()
                                        .setType(AclEntryType.ALLOW)
                                        .setPrincipal(Files.getOwner(temporary))
                                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                        .build()));
            }
            var posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            }
            try {
                Files.move(temporary, stateFile(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, stateFile());
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void readLease() throws Exception {
        var data = mapper.readTree(stateFile().toFile());
        if (data.path("schemaVersion").asInt() != 1
                || !data.path("original").isObject()
                || !data.path("applied").isObject()) {
            throw new IOException("系统代理恢复文件格式无效，未修改系统设置");
        }
        for (String key : List.of("original", "applied")) {
            var snapshot = data.get(key);
            if (!snapshot.path("flags").isIntegralNumber()
                    || !snapshot.path("flags").canConvertToInt()
                    || !snapshot.path("server").isTextual()
                    || !snapshot.path("bypass").isTextual()
                    || !snapshot.path("autoConfigUrl").isTextual()) {
                throw new IOException("系统代理恢复快照字段无效，未修改系统设置");
            }
        }
        original = mapper.treeToValue(data.get("original"), ProxySettings.class);
        applied = mapper.treeToValue(data.get("applied"), ProxySettings.class);
    }

    private String fingerprint(ProxySettings settings) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(mapper.writeValueAsBytes(settings)));
    }

    private void forgetLease() throws IOException {
        Files.deleteIfExists(stateFile());
        original = null;
        applied = null;
        releaseLock();
    }

    private void releaseLock() throws IOException {
        try {
            if (lease != null) {
                lease.release();
            }
        } finally {
            lease = null;
            if (channel != null) {
                channel.close();
                channel = null;
            }
        }
    }

    @Override
    public synchronized void close() throws Exception {
        closed = true;
        try {
            if (applied != null) {
                restore();
            }
        } finally {
            try {
                releaseLock();
            } finally {
                backend.close();
            }
        }
    }
}
