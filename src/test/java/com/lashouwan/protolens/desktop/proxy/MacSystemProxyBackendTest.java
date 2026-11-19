package com.lashouwan.protolens.desktop.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** 不修改本机网络：覆盖多服务选择、授权前后检查、恢复定位和拒绝无法备份密码的代理。 */
class MacSystemProxyBackendTest {
    @TempDir Path directory;

    static MacProxySettings settings(String service, String device) {
        var values = new LinkedHashMap<String, String>();
        values.put("http", "Enabled: No\nServer: \nPort: 0\nAuthenticated Proxy Enabled: 0");
        values.put("https", values.get("http"));
        values.put("pac", "URL: http://private.test/pac?secret=local\nEnabled: Yes");
        values.put("discovery", "Auto Proxy Discovery: On");
        values.put("socks", "Enabled: No\nServer: \nPort: 0\nAuthenticated Proxy Enabled: 0");
        values.put("bypass", "localhost\n*.private.test");
        return new MacProxySettings(service, device, values);
    }

    @Test
    void choosesExactEnabledNetworkServiceAndRejectsVpnOrAmbiguity() throws Exception {
        String order =
                "An asterisk denotes disabled services.\n"
                        + "(1) USB LAN\n"
                        + "(Hardware Port: LAN, Device: en10)\n"
                        + "(2) 办公 Wi-Fi\n"
                        + "(Hardware Port: Wi-Fi, Device: en0)\n"
                        + "(*) Disabled\n"
                        + "(Hardware Port: LAN, Device: en2)";
        assertEquals("办公 Wi-Fi", MacSystemProxyBackend.serviceForDevice(order, "en0"));
        assertThrows(IOException.class, () -> MacSystemProxyBackend.serviceForDevice(order, "en1"));
        assertThrows(
                IOException.class, () -> MacSystemProxyBackend.serviceForDevice(order, "utun0"));
        assertThrows(IOException.class, () -> MacSystemProxyBackend.serviceForDevice(order, "en2"));
        assertThrows(
                IOException.class,
                () ->
                        MacSystemProxyBackend.serviceForDevice(
                                order + "\n(3) Duplicate\n(Hardware Port: Wi-Fi, Device: en0)",
                                "en0"));
    }

    @Test
    void localSettingsPreservePacUrlBypassAndRoundTripThroughLeaseJson() throws Exception {
        var original = settings("办公 Wi-Fi", "en0");
        var applied = original.local(18996);
        assertTrue(applied.pointsTo(18996));
        assertFalse(applied.pointsTo(18888));
        assertEquals(original.values().get("bypass"), applied.values().get("bypass"));
        assertEquals(
                MacProxySettings.field(original.values().get("pac"), "URL"),
                MacProxySettings.field(applied.values().get("pac"), "URL"));
        assertFalse(applied.enabled("pac"));
        assertFalse(applied.enabled("discovery"));
        var mapper = new ObjectMapper();
        assertEquals(
                original.snapshot(),
                mapper.readValue(
                        mapper.writeValueAsBytes(original.snapshot()), ProxySettings.class));
        assertNull(
                mapper.readValue(
                                "{\"flags\":1,\"server\":\"\",\"bypass\":\"\",\"autoConfigUrl\":\"\"}",
                                ProxySettings.class)
                        .mac());
    }

    @Test
    void rejectsAuthenticationAndSocksBeforeWriting() {
        var original = settings("Wi-Fi", "en0");
        var values = new LinkedHashMap<>(original.values());
        values.put("https", values.get("https").replace("Enabled: 0", "Enabled: 1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MacProxySettings("Wi-Fi", "en0", values).local(18888));
        values.put("https", original.values().get("https"));
        values.put("socks", values.get("socks").replace("Enabled: No", "Enabled: Yes"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MacProxySettings("Wi-Fi", "en0", values).local(18888));
    }

    @Test
    void restoreReadsOriginalServiceAfterNetworkChangesAndPromptsOnlyForWrite() throws Exception {
        var original = settings("Wi-Fi", "en0");
        var runner = new FakeCommands(original);
        var backend = new MacSystemProxyBackend(runner);
        assertEquals(original.snapshot(), backend.read());
        runner.device = "en5";
        assertEquals(original.snapshot(), backend.readFor(original.snapshot()));
        assertTrue(backend.notice(original.snapshot()).contains("网络接口已切换"));
        assertEquals(0, runner.authorizations);
        backend.compareAndWrite(original.snapshot(), original.local(18888).snapshot());
        assertEquals(1, runner.authorizations);
        runner.current = original.local(18888);
        assertThrows(
                IOException.class,
                () ->
                        backend.compareAndWrite(
                                original.snapshot(), original.local(18888).snapshot()));
        assertEquals(1, runner.authorizations);
    }

    @Test
    void macStartupNeverWritesAndPartialFailureRetainsBackup() throws Exception {
        var original = settings("Wi-Fi", "en0");
        final ProxySettings[] current = {original.snapshot()};
        var backend =
                new SystemProxyBackend() {
                    public boolean supported() {
                        return true;
                    }

                    public boolean requiresAuthorization() {
                        return true;
                    }

                    public ProxySettings read() {
                        return current[0];
                    }

                    public void write(ProxySettings value) {
                        current[0] = value;
                    }

                    public ProxySettings local(int port, ProxySettings value) {
                        return value.mac().local(port).snapshot();
                    }
                };
        var manager = new SystemProxyManager(backend, directory, 18888, () -> true);
        var initial = manager.initialize(true);
        assertFalse(initial.path("managed").asBoolean());
        assertFalse(initial.path("autoPrompt").asBoolean());
        assertEquals(original.snapshot(), current[0]);
        manager.connect(true, initial.path("confirmationToken").asText());
        var partial = new LinkedHashMap<>(original.values());
        partial.put("http", current[0].mac().values().get("http"));
        current[0] = new MacProxySettings("Wi-Fi", "en0", partial).snapshot();
        assertThrows(IOException.class, manager::restore);
        assertThrows(IOException.class, () -> manager.connect(true, ""));
        assertTrue(Files.isRegularFile(directory.resolve("proxy-lease.json")));
        current[0] = original.snapshot();
        manager.restore();
        manager.close();
        assertFalse(Files.exists(directory.resolve("proxy-lease.json")));
    }

    @Test
    void scriptQuotesUntrustedNamesChecksSnapshotAfterAuthorizationAndAddsRollback() {
        var original = settings("办公 ' $(touch /tmp/should-not-run) \\\"", "en0");
        String script = MacSystemProxyBackend.transaction(original, original.local(18888));
        assertTrue(script.indexOf("-getwebproxy") < script.indexOf("-setwebproxy"));
        assertTrue(script.contains("trap 'rollback' EXIT"));
        assertTrue(script.contains("$after_http"));
        assertTrue(script.contains("secret=local"));
        assertFalse(script.contains("-setautoproxyurl "));
        assertFalse(script.contains("-setproxybypassdomains "));
        assertFalse(script.contains("-setsocksfirewallproxy "));
        assertEquals("'a'\"'\"'b'", MacSystemProxyBackend.quote("a'b"));
        assertEquals("\"a\\n\\\"b\\\\\"", MacSystemProxyBackend.appleString("a\n\"b\\"));
    }

    /** macOS CI 只编译授权脚本语法，不运行它，不弹系统授权，也不改网络。 */
    @Test
    @EnabledOnOs(OS.MAC)
    void nativeAppleScriptCompilesWithoutExecuting() throws Exception {
        var original = settings("Wi-Fi", "en0");
        String script =
                "do shell script "
                        + MacSystemProxyBackend.appleString(
                                MacSystemProxyBackend.transaction(original, original.local(18888)))
                        + " with administrator privileges with prompt \"ProtoLens 代理授权\"";
        Path source = directory.resolve("proxy.applescript");
        Files.writeString(source, script);
        Process process =
                new ProcessBuilder(
                                "/usr/bin/osacompile",
                                "-o",
                                directory.resolve("proxy.scpt").toString(),
                                source.toString())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static class FakeCommands implements MacSystemProxyBackend.CommandRunner {
        MacProxySettings current;
        String device = "en0";
        int authorizations;

        FakeCommands(MacProxySettings current) {
            this.current = current;
        }

        public String run(List<String> command, boolean authorized) {
            if (authorized) {
                authorizations++;
                return "";
            }
            if (command.getFirst().endsWith("scutil")) {
                return "Network interfaces: " + device;
            }
            if (command.get(1).equals("-listnetworkserviceorder")) {
                return "(1) " + current.service() + "\n(Hardware Port: Wi-Fi, Device: en0)";
            }
            assertEquals(current.service(), command.get(2));
            String key =
                    switch (command.get(1)) {
                        case "-getwebproxy" -> "http";
                        case "-getsecurewebproxy" -> "https";
                        case "-getautoproxyurl" -> "pac";
                        case "-getproxyautodiscovery" -> "discovery";
                        case "-getsocksfirewallproxy" -> "socks";
                        case "-getproxybypassdomains" -> "bypass";
                        default -> throw new AssertionError(command);
                    };
            return current.values().get(key);
        }
    }
}
