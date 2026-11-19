import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

// 只在对应 CPU 的 macOS 上验收真实 DMG；从安装后的副本启动，不能以源码测试替代发行包测试。
const [dmgArgument, platform] = process.argv.slice(2);
assert.equal(process.platform, "darwin", "DMG 验收必须在 macOS 执行");
const architecture = process.arch === "arm64" ? "arm64" : "x64";
assert.equal(platform, `macos-${architecture}`, "构建机与发布架构不一致");
assert.ok(dmgArgument, "需要提供 DMG 路径");
const dmg = resolve(dmgArgument);
const scratch = mkdtempSync(join(tmpdir(), "protolens-dmg-"));
const mount = join(scratch, "mount");
const installation = join(scratch, "Applications 测试目录");
const app = join(installation, "ProtoLens.app");
const launcher = join(app, "Contents/MacOS/ProtoLens");
let mounted = false;

function command(executable, args, options = {}) {
  return execFileSync(executable, args, {
    encoding: "utf8",
    timeout: 120_000,
    ...options,
  }).trim();
}

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const port = server.address().port;
  await new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
  return port;
}

// Launch Services 的 open 进程与应用进程不同，只终止明确带有本次独立配置路径的应用 PID。
function applicationPid(config) {
  const lines = command("/bin/ps", ["-axo", "pid=,command="]).split("\n");
  const line = lines.find(
    (line) => line.includes(launcher) && line.includes(config),
  );
  return line ? Number(line.trim().split(/\s+/)[0]) : undefined;
}

async function smoke(mode) {
  const directory = join(scratch, mode);
  mkdirSync(directory);
  const config = join(directory, "analyzer.json");
  const data = join(directory, "data");
  writeFileSync(
    config,
    JSON.stringify({
      serverPort: 0,
      collectorPort: await freePort(),
      frontendMode: "static",
      openBrowser: false,
      autoSystemProxy: false,
      autoStartCollector: true,
      protocolAutoLoad: false,
      competitorPath: "",
    }),
    "utf8",
  );
  const args = [
    "--config",
    config,
    "--data-dir",
    data,
    "--open-browser",
    "false",
  ];
  const log = [];
  // 故意不给开发机的 Java、Node 和 Python PATH；本机启动器必须使用包内 Java。
  const env = {
    ...process.env,
    JAVA_HOME: "/nonexistent-protolens-java",
    PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
  };
  delete env.JDK_JAVA_OPTIONS;
  delete env.JAVA_TOOL_OPTIONS;
  delete env._JAVA_OPTIONS;
  const child =
    mode === "native"
      ? spawn(launcher, args, {
          cwd: "/",
          env,
          stdio: ["ignore", "pipe", "pipe"],
        })
      : spawn("/usr/bin/open", ["-W", "-n", app, "--args", ...args], {
          cwd: "/",
          env,
          stdio: ["ignore", "pipe", "pipe"],
        });
  child.stdout.on("data", (value) => log.push(value.toString()));
  child.stderr.on("data", (value) => log.push(value.toString()));
  let spawnError;
  child.on("error", (error) => {
    spawnError = error;
  });
  let pid;
  try {
    let origin;
    for (let attempt = 0; attempt < 180; attempt++) {
      assert.ifError(spawnError);
      assert.equal(child.exitCode, null, `应用启动提前退出：${log.join("")}`);
      pid ??= mode === "native" ? child.pid : applicationPid(config);
      const metadata = join(data, "run/instance.json");
      if (existsSync(metadata)) {
        const instance = JSON.parse(readFileSync(metadata, "utf8"));
        origin = `http://127.0.0.1:${instance.port}`;
        break;
      }
      await delay(500);
    }
    assert.ok(origin, "90 秒内未就绪");
    assert.ok(pid, "无法定位本次应用进程");
    const health = await fetch(`${origin}/api/health`).then((response) =>
      response.json(),
    );
    assert.equal(health.ready, true);
    const settings = await fetch(`${origin}/api/settings`).then((response) =>
      response.json(),
    );
    assert.equal(settings.dataDirectory, data, "数据必须写入用户目录");
    assert.equal(settings.openBrowser, false);
    const page = await fetch(origin).then((response) => response.text());
    assert.match(page, /<html/i, "包内前端资源未正确定位");
    const collector = await fetch(`${origin}/api/collector`).then((response) =>
      response.json(),
    );
    assert.equal(collector.state, "RUNNING", JSON.stringify(collector));
    assert.ok(
      existsSync(join(data, "mitmproxy-conf/mitmproxy-ca-cert.cer")),
      "采集器必须能生成用户证书",
    );
    assert.equal(
      existsSync(join(app, "Contents/app/data")),
      false,
      "不能向安装目录写入用户数据",
    );
    console.log(
      `${platform}: ${mode} 启动、静态页面、用户数据目录与随包采集器检查通过`,
    );
  } catch (error) {
    console.error(log.join(""));
    for (const name of ["analyzer.log", "collector.log"]) {
      const path = join(data, "logs", name);
      if (existsSync(path))
        console.error(readFileSync(path, "utf8").slice(-12_000));
    }
    throw error;
  } finally {
    pid ??= mode === "native" ? child.pid : applicationPid(config);
    if (pid) {
      try {
        process.kill(pid, "SIGTERM");
      } catch (error) {
        if (error.code !== "ESRCH") throw error;
      }
      for (let attempt = 0; attempt < 100; attempt++) {
        try {
          process.kill(pid, 0);
        } catch {
          pid = undefined;
          break;
        }
        await delay(200);
      }
      if (pid) process.kill(pid, "SIGKILL");
    }
    // open -W 只负责等待本次应用，不是应用本身；退出应用后清理等待进程。
    if (child.exitCode === null) child.kill("SIGTERM");
  }
}

try {
  command("/usr/bin/hdiutil", ["verify", dmg]);
  mkdirSync(mount);
  command("/usr/bin/hdiutil", [
    "attach",
    "-readonly",
    "-nobrowse",
    "-mountpoint",
    mount,
    dmg,
  ]);
  mounted = true;
  assert.ok(
    existsSync(join(mount, "ProtoLens.app")),
    "DMG 必须包含可拖入应用程序的 .app",
  );
  assert.ok(
    existsSync(join(mount, "Applications")),
    "DMG 必须包含 Applications 入口",
  );
  mkdirSync(installation);
  command("/usr/bin/ditto", [join(mount, "ProtoLens.app"), app]);
  const root = join(app, "Contents/app");
  const javaHome = join(app, "Contents/runtime/Contents/Home");
  const expectedMachArch = architecture === "arm64" ? "arm64" : "x86_64";
  for (const binary of [
    launcher,
    join(javaHome, "lib/server/libjvm.dylib"),
    join(root, "runtime-tools/node"),
    join(root, "runtime-tools/mitmdump"),
  ]) {
    assert.ok(existsSync(binary), `安装包缺少运行文件：${binary}`);
    assert.ok(
      command("/usr/bin/lipo", ["-archs", binary])
        .split(/\s+/)
        .includes(expectedMachArch),
      `运行文件 CPU 架构错误：${binary}`,
    );
  }
  assert.match(
    readFileSync(join(javaHome, "release"), "utf8"),
    /JAVA_VERSION="21[."]/,
  );
  const node = join(root, "runtime-tools/node");
  assert.equal(
    command(node, ["-p", 'process.platform+"-"+process.arch']),
    `darwin-${process.arch}`,
  );
  command(
    node,
    [
      "-e",
      'require("./web/node_modules/acorn").parse("const a = 1", {ecmaVersion:2022})',
    ],
    { cwd: root },
  );
  assert.match(
    command(join(root, "runtime-tools/mitmdump"), ["--version"]),
    /Mitmproxy:/,
  );
  await smoke("native");
  // open 使用与 Finder 双击相同的 Launch Services 路径；此项也必须成功才能上传发布产物。
  await smoke("finder");
  console.log(`DMG 验收通过：${basename(dmg)}`);
} finally {
  if (mounted) command("/usr/bin/hdiutil", ["detach", mount]);
  rmSync(scratch, { recursive: true, force: true });
}
