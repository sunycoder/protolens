package com.lashouwan.protolens.desktop.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 代理测试全部使用内存后端，覆盖确认、跨实例所有权、崩溃及恢复失败，不改变开发机网络。 */
class SystemProxyManagerTest {
    @TempDir Path directory;
    private static final ProxySettings DIRECT = new ProxySettings(1, "", "<local>", "");
    private static final ProxySettings VPN =
            new ProxySettings(
                    7,
                    "http=127.0.0.1:7890;https=127.0.0.1:7890",
                    "private.test",
                    "http://127.0.0.1/pac?private=yes");
    private final MemoryBackend backend = new MemoryBackend(DIRECT);

    private SystemProxyManager manager() {
        return new SystemProxyManager(backend, directory, 18888, () -> true);
    }

    @Test
    void directConnectionAutoConnectsAndExitRestoresExactly() throws Exception {
        try (var manager = manager()) {
            assertTrue(manager.initialize(true).path("managed").asBoolean());
            assertEquals(3, backend.current.flags());
            assertTrue(backend.current.server().contains("127.0.0.1:18888"));
            assertTrue(Files.isRegularFile(directory.resolve("proxy-lease.json")));
        }
        assertEquals(DIRECT, backend.current);
        assertFalse(Files.exists(directory.resolve("proxy-lease.json")));
        assertEquals(2, backend.writes);
    }

    @Test
    void existingVpnRequiresConfirmationAndRestoresPacAndBypass() throws Exception {
        backend.current = VPN;
        try (var manager = manager()) {
            var preview = manager.initialize(true);
            assertTrue(preview.path("autoPrompt").asBoolean());
            assertTrue(preview.path("confirmationRequired").asBoolean());
            assertEquals(0, backend.writes);
            manager.decline();
            assertFalse(manager.status().path("autoPrompt").asBoolean());
            assertEquals(VPN, backend.current);
            assertFalse(manager.connect(true, "stale").path("managed").asBoolean());
            assertEquals(0, backend.writes);
            String token = manager.status().path("confirmationToken").asText();
            assertTrue(manager.connect(true, token).path("managed").asBoolean());
        }
        assertEquals(VPN, backend.current);
    }

    @Test
    void changedSettingsInvalidatePriorConfirmation() throws Exception {
        backend.current = VPN;
        try (var manager = manager()) {
            String token = manager.initialize(true).path("confirmationToken").asText();
            var changed = new ProxySettings(2, "other.test:8080", "different", "");
            backend.current = changed;
            var result = manager.connect(true, token);
            assertFalse(result.path("managed").asBoolean());
            assertTrue(result.path("confirmationRequired").asBoolean());
            assertEquals(changed, backend.current);
            assertEquals(0, backend.writes);
        }
    }

    @Test
    void externalVpnChangeIsNeverOverwrittenOnExit() throws Exception {
        try (var manager = manager()) {
            manager.initialize(true);
            backend.current = VPN;
        }
        assertEquals(VPN, backend.current);
        assertEquals(1, backend.writes);
        assertFalse(Files.exists(directory.resolve("proxy-lease.json")));
    }

    @Test
    void legacyManualCaptureProxyRequiresConsentAndIsDisabledOnExit() throws Exception {
        var legacy = new ProxySettings(3, "127.0.0.1:18888", "<local>", "");
        backend.current = legacy;
        try (var manager = manager()) {
            var preview = manager.initialize(true);
            assertTrue(preview.path("existingIsTarget").asBoolean());
            assertEquals(0, backend.writes);
            manager.connect(true, preview.path("confirmationToken").asText());
        }
        assertEquals(1, backend.current.flags());
        assertEquals(legacy.server(), backend.current.server());
    }

    @Test
    void onlyOneWorkbenchCanOwnUserProxy() throws Exception {
        try (var first = manager();
                var second = manager()) {
            first.initialize(true);
            var preview = second.status();
            assertThrows(
                    IOException.class,
                    () -> second.connect(true, preview.path("confirmationToken").asText()));
            assertTrue(first.status().path("managed").asBoolean());
            assertEquals(1, backend.writes);
        }
        assertEquals(DIRECT, backend.current);
    }

    @Test
    void abandonedLeaseRestoresOnNextAutomaticStartup() throws Exception {
        ProxySettings applied = ProxySettings.local(18888, VPN);
        var mapper = new ObjectMapper();
        var saved = mapper.createObjectNode().put("schemaVersion", 1);
        saved.set("original", mapper.valueToTree(VPN));
        saved.set("applied", mapper.valueToTree(applied));
        mapper.writeValue(directory.resolve("proxy-lease.json").toFile(), saved);
        backend.current = applied;
        try (var manager = manager()) {
            var state = manager.initialize(true);
            assertEquals(VPN, backend.current);
            assertTrue(state.path("confirmationRequired").asBoolean());
            assertEquals(1, backend.writes);
        }
        assertFalse(Files.exists(directory.resolve("proxy-lease.json")));
    }

    @Test
    void unavailableCollectorAndDisabledAutoNeverWriteSystemSettings() throws Exception {
        try (var manager = new SystemProxyManager(backend, directory, 18888, () -> false)) {
            manager.initialize(true);
            assertThrows(IllegalArgumentException.class, () -> manager.connect(true, ""));
        }
        try (var manager = manager()) {
            manager.initialize(false);
        }
        assertEquals(0, backend.writes);
    }

    @Test
    void failedRestoreKeepsBackupAndNextRunCanRetry() throws Exception {
        var first = manager();
        first.initialize(true);
        backend.fail = true;
        assertThrows(IOException.class, first::close);
        assertTrue(Files.exists(directory.resolve("proxy-lease.json")));
        backend.fail = false;
        try (var recovered = manager()) {
            recovered.restore();
        }
        assertEquals(DIRECT, backend.current);
        assertFalse(Files.exists(directory.resolve("proxy-lease.json")));
    }

    private static final class MemoryBackend implements SystemProxyBackend {
        ProxySettings current;
        int writes;
        boolean fail;

        MemoryBackend(ProxySettings current) {
            this.current = current;
        }

        public boolean supported() {
            return true;
        }

        public ProxySettings read() {
            return current;
        }

        public void write(ProxySettings settings) throws IOException {
            if (fail) {
                throw new IOException("模拟系统写入失败");
            }
            current = settings;
            writes++;
        }
    }
}
