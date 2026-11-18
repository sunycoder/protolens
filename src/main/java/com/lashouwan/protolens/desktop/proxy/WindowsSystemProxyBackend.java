package com.lashouwan.protolens.desktop.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 通过随包 WinINet 脚本管理当前用户默认 LAN；不需要管理员权限，也不操作 VPN/TUN 或 WinHTTP。 */
public final class WindowsSystemProxyBackend implements SystemProxyBackend {
    private final Path script;
    private final ObjectMapper mapper = new ObjectMapper();

    public WindowsSystemProxyBackend(Path script) {
        this.script = script;
    }

    @Override
    public boolean supported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    public ProxySettings read() throws Exception {
        return execute("Read", null);
    }

    @Override
    public void write(ProxySettings settings) throws Exception {
        execute("Write", settings);
    }

    @Override
    public void compareAndWrite(ProxySettings expected, ProxySettings settings) throws Exception {
        var input = mapper.createObjectNode();
        input.set("expected", mapper.valueToTree(expected));
        input.set("settings", mapper.valueToTree(settings));
        execute("CompareWrite", input);
    }

    private ProxySettings execute(String action, Object value) throws Exception {
        if (!supported() || !Files.isRegularFile(script)) {
            throw new IOException("此环境无法使用随包 Windows 系统代理适配器");
        }
        Process process =
                new ProcessBuilder(
                                "powershell.exe",
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-WindowStyle",
                                "Hidden",
                                "-ExecutionPolicy",
                                "Bypass",
                                "-File",
                                script.toString(),
                                "-Action",
                                action)
                        .redirectErrorStream(true)
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                if (value != null) {
                    input.write(mapper.writeValueAsBytes(value));
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new IOException("系统代理操作超时，未确认执行结果");
            }
            byte[] output = process.getInputStream().readNBytes(65536);
            if (process.exitValue() == 3) {
                throw new IOException("系统代理在操作前已变化，未覆盖；请重新确认");
            }
            if (process.exitValue() != 0) {
                // 不记录原代理地址或 PAC 凭据；脚本只返回 Win32 错误类型及编号。
                throw new IOException("WinINet 系统代理操作失败，原因尚未确定，退出码 " + process.exitValue());
            }
            return mapper.readValue(
                    new String(output, StandardCharsets.UTF_8).strip(), ProxySettings.class);
        } finally {
            if (process.isAlive()) {
                for (ProcessHandle child : process.descendants().toList()) {
                    child.destroyForcibly();
                }
                process.destroyForcibly();
            }
        }
    }
}
