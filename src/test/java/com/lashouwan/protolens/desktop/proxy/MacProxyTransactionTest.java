package com.lashouwan.protolens.desktop.proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 在独立目录用假的 networksetup 执行真实 shell 事务，验证写入中途失败和授权期间变更。 */
class MacProxyTransactionTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void takeoverAndRestorePreserveAllOriginalSettings() throws Exception {
        var original = MacSystemProxyBackendTest.settings("办公 Wi-Fi ' $(echo injection)", "en0");
        var local = original.local(18996);
        setup(original);
        assertEquals(0, execute(original, local, false));
        assertEquals(
                mapper.valueToTree(local.values()),
                mapper.readTree(directory.resolve("state.json").toFile()));
        assertEquals(0, execute(local, original, false));
        assertEquals(
                mapper.valueToTree(original.values()),
                mapper.readTree(directory.resolve("state.json").toFile()));
    }

    @Test
    void intermediateWriteFailureRollsBackCompletedCategories() throws Exception {
        var original = MacSystemProxyBackendTest.settings("Wi-Fi", "en0");
        setup(original);
        assertNotEquals(0, execute(original, original.local(18888), true));
        assertEquals(
                mapper.valueToTree(original.values()),
                mapper.readTree(directory.resolve("state.json").toFile()));
    }

    @Test
    void changedConfigurationDuringAuthorizationIsNeverOverwritten() throws Exception {
        var original = MacSystemProxyBackendTest.settings("Wi-Fi", "en0");
        var external = original.local(7890);
        setup(external);
        assertEquals(3, execute(original, original.local(18888), false));
        assertEquals(
                mapper.valueToTree(external.values()),
                mapper.readTree(directory.resolve("state.json").toFile()));
    }

    private void setup(MacProxySettings original) throws Exception {
        mapper.writeValue(directory.resolve("state.json").toFile(), original.values());
        Files.writeString(
                directory.resolve("networksetup.mjs"),
                """
import fs from 'node:fs';
const [state, command, service, ...args] = process.argv.slice(2);
const values = JSON.parse(fs.readFileSync(state, 'utf8'));
const names = {webproxy:'http', securewebproxy:'https', autoproxyurl:'pac',
  proxyautodiscovery:'discovery', socksfirewallproxy:'socks', proxybypassdomains:'bypass'};
if (command.startsWith('-get')) {
  console.log(values[names[command.slice(4)]]); process.exit(0);
}
const replace = (key, field, value) => {
  values[key] = values[key].split('\\n').map(line => line.startsWith(field+':') ? field+': '+value : line).join('\\n');
};
if (command === '-setsecurewebproxystate' && process.env.FAIL_ONCE === '1' && !fs.existsSync(state+'.failed')) {
  fs.writeFileSync(state+'.failed', 'yes'); process.exit(9);
}
if (command === '-setwebproxy' || command === '-setsecurewebproxy') {
  const key = command === '-setwebproxy' ? 'http' : 'https';
  replace(key, 'Server', args[0]); replace(key, 'Port', args[1]); replace(key, 'Enabled', 'Yes');
} else if (command === '-setwebproxystate' || command === '-setsecurewebproxystate') {
  replace(command === '-setwebproxystate' ? 'http' : 'https', 'Enabled', args[0] === 'on' ? 'Yes' : 'No');
} else if (command === '-setautoproxystate') {
  replace('pac', 'Enabled', args[0] === 'on' ? 'Yes' : 'No');
} else if (command === '-setproxyautodiscovery') {
  replace('discovery', 'Auto Proxy Discovery', args[0] === 'on' ? 'On' : 'Off');
} else { process.exit(7); }
fs.writeFileSync(state, JSON.stringify(values));
""");
    }

    private int execute(MacProxySettings expected, MacProxySettings target, boolean fail)
            throws Exception {
        Path bash = Path.of("D:/dev/Git/bin/bash.exe");
        if (!Files.isRegularFile(bash)) {
            bash = Path.of("/bin/bash");
        }
        assumeTrue(Files.isRegularFile(bash), "需要 bash 执行隔离事务测试");
        String fake =
                "node "
                        + MacSystemProxyBackend.quote(
                                directory.resolve("networksetup.mjs").toString().replace('\\', '/'))
                        + " "
                        + MacSystemProxyBackend.quote(
                                directory.resolve("state.json").toString().replace('\\', '/'));
        Path script = directory.resolve("transaction.sh");
        Files.writeString(
                script,
                MacSystemProxyBackend.transaction(expected, target)
                        .replace("/usr/sbin/networksetup", fake));
        var builder =
                new ProcessBuilder(List.of(bash.toString(), script.toString().replace('\\', '/')))
                        .redirectErrorStream(true);
        if (fail) {
            builder.environment().put("FAIL_ONCE", "1");
        }
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
    }
}
