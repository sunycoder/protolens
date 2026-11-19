package com.lashouwan.protolens.desktop.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** 使用真实子进程验证授权会话复用与收尾，不获取系统授权或改动本机网络。 */
class MacProxyAuthorizationSessionTest {
    /** 接管、恢复和再次接管都走同一个子进程，恢复操作不能销毁授权会话。 */
    @Test
    void reusesOneSessionUntilWorkbenchCloses() throws Exception {
        var processes = new ArrayList<Process>();
        var original = MacSystemProxyBackendTest.settings("办公 Wi-Fi ' $(echo test)", "en0");
        var session =
                new MacProxyAuthorizationSession(
                        () -> {
                            Process process = fixture("success");
                            processes.add(process);
                            return process;
                        });
        try {
            session.write(original, original.local(18888));
            session.write(original.local(18888), original);
            session.write(original, original.local(18888));
            assertEquals(1, processes.size());
            assertTrue(processes.getFirst().isAlive());
        } finally {
            session.close();
        }
        assertFalse(processes.getFirst().isAlive());
        assertEquals(0, processes.getFirst().exitValue(), "关闭 stdin 应让组件正常退出");
    }

    /** 系统明确拒绝时只发送一次；不重新启动子进程、重复弹窗或自动重放写操作。 */
    @Test
    void rejectionDoesNotRestartOrReplay() throws Exception {
        var processes = new ArrayList<Process>();
        var original = MacSystemProxyBackendTest.settings("Wi-Fi", "en0");
        try (var session =
                new MacProxyAuthorizationSession(
                        () -> {
                            Process process = fixture("reject-once");
                            processes.add(process);
                            return process;
                        })) {
            var error =
                    assertThrows(
                            java.io.IOException.class,
                            () -> session.write(original, original.local(18888)));
            assertEquals("已取消授权", error.getMessage());
            assertEquals(1, processes.size());
            // 第二个由用户显式触发的请求应成功；若实现自动重放，第一个调用就会错误地成功。
            session.write(original, original.local(18888));
            assertEquals(1, processes.size());
        }
    }

    /** 组件断开时报告结果未知并回收；必须由用户重试，不能在同一调用内自动重放。 */
    @Test
    void disconnectStopsWithoutAutomaticReplay() throws Exception {
        var original = MacSystemProxyBackendTest.settings("Wi-Fi", "en0");
        var processes = new ArrayList<Process>();
        try (var session =
                new MacProxyAuthorizationSession(
                        () -> {
                            Process process = fixture("disconnect");
                            processes.add(process);
                            return process;
                        })) {
            assertThrows(
                    java.io.IOException.class,
                    () -> session.write(original, original.local(18888)));
            assertEquals(1, processes.size());
            assertFalse(processes.getFirst().isAlive());
        }
    }

    /** macOS CI 编译原生组件后只校验请求，不弹授权、不改设置；非法请求不能进入授权阶段。 */
    @Test
    @EnabledOnOs(OS.MAC)
    void nativeValidatorAcceptsSnapshotsAndRejectsBroaderOperations() throws Exception {
        var original = MacSystemProxyBackendTest.settings("Wi-Fi ' 中文", "en0");
        var mapper = new ObjectMapper();
        Path executable = Path.of("build/macos-native/ProtoLensProxySession").toAbsolutePath();
        Process process = new ProcessBuilder(executable.toString(), "--validate-only").start();
        try {
            var request = mapper.createObjectNode();
            request.set("expected", mapper.valueToTree(original));
            request.set("target", mapper.valueToTree(original.local(18888)));
            var input = process.outputWriter(StandardCharsets.UTF_8);
            var output = process.inputReader(StandardCharsets.UTF_8);
            input.write(mapper.writeValueAsString(request) + "\n");
            input.flush();
            assertTrue(mapper.readTree(output.readLine()).path("ok").asBoolean());
            request.withObject("target").put("device", "en1");
            input.write(mapper.writeValueAsString(request) + "\n");
            input.flush();
            assertFalse(mapper.readTree(output.readLine()).path("ok").asBoolean());
            input.write("{\"command\":\"id\"}\n");
            input.flush();
            assertFalse(mapper.readTree(output.readLine()).path("ok").asBoolean());
            input.close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        } finally {
            process.destroyForcibly();
        }
    }

    private static Process fixture(String mode) throws Exception {
        String classpath =
                Path.of(
                                MacProxyAuthorizationSessionTest.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dstdout.encoding=UTF-8",
                        "-Dfile.encoding=UTF-8",
                        "-cp",
                        classpath,
                        Fixture.class.getName(),
                        mode)
                .start();
    }

    /** 仅模拟管道协议，无授权能力；每份请求对应一份响应。 */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            var reader =
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            int requests = 0;
            while (reader.readLine() != null) {
                if (args[0].equals("disconnect")) return;
                if (args[0].equals("reject-once") && requests++ == 0) {
                    System.out.println("{\"ok\":false,\"message\":\"已取消授权\"}");
                } else System.out.println("{\"ok\":true}");
                System.out.flush();
            }
        }
    }
}
