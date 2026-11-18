# 首版验收记录

验收日期：2026-11-09。以下是本次实际运行结果，不代表任意竞品版本都能完全映射。

## 编译与聚焦验证

- 独立构建 `gradlew -p protolens test`：通过，Java 21。
- 根工程任务 `gradlew :protolens:test`：通过，已确认模块注册可用。
- Java 聚焦解析测试：7 项通过，0 失败，0 错误。
- `node --test tools/extract-catalog.test.mjs`：2 项通过，0 失败。
- `npm.cmd run build`：TypeScript 检查和 Vite 生产构建通过。
- 一键启动脚本对应的完整构建链 `gradlew -p protolens webBuild extractCatalog -PcompetitorDir=<客户端目录>`：通过，实际包含 npm ci、生产构建和字典生成；随后本机服务健康检查 ready=true。
- 浏览器实际打开本机生产页面：服务状态、记录加载、协议名称搜索、消息列表、JSON 树和完整时间显示已验证。
- 浏览器通过键盘打开并保存了“联盟科技捐献：两次操作观察区间”，范围 #387～#399；之后 API 读取确认已持久化。反向区间请求返回 HTTP 400。
- 最终页面控制台：0 错误、0 警告。浏览器扩展的截图与部分鼠标点击等待稳定性超时，因此未把截图列为交付证据；键盘操作和 DOM 读取可正常验证页面。

## 用户提供的真实 SAZ

输入文件名：`2026-11-09-161257.saz`。原件未修改，抓包与解码业务数据未加入 SVN。

| 项目 | 实际结果 |
| --- | ---: |
| WebSocket 连接 | 1 |
| 业务消息总数 | 20,422 |
| 完整匹配字典 DECODED | 14,488 |
| 含未知字段或其他警告 PARTIAL | 5,835 |
| 缺少消息名 UNMAPPED | 99 |
| 帧解封装或包体解码 FAILED | 0 |
| 静态字典消息数 | 2,688 |
| 消息及结构体字段定义数 | 3,226 |

字典 SHA-256：`52a34348db1d39e787a0f309e395a21aa2debd212d27113ebaf91afda288a38d`。

源 SAZ SHA-256：`79929e909ca55ba1a7ec6b65e83ad62c254013dba93be66977c058c5ca9a0cce`。

科技捐献发送包两次均完整解码：

```json
{
  "protocolId": 2364,
  "protocolName": "CgAllianceTechDonate",
  "body": {
    "techGroupId": 1017,
    "isUseCp": false,
    "level": 4
  }
}
```

列表编号分别为 #387、#396；对应时间为输入文件记录的北京时间 12:33:43.610、12:33:50.079。协议 `GcAllianceTechDonate`（2365）在 #389、#398 也完整解码，文件后续还存在同名服务端消息，因此没有把所有同名下发自动归类为本玩家这两次请求的响应。

`PARTIAL` 和 `UNMAPPED` 表明本地客户端字典与实际运行包存在覆盖差异；工具保留原始字节及未知字段，不给它们杜撰业务名称。此次读取失败为 0，不等于所有消息的业务语义均已确认。

## 交付边界

后续实时接入已完成：独立 mitmproxy 采集器、本机增量接收与 SSE 页面均已接通，真实科技捐献已验证。以下“首版范围”描述保留作为离线版阶段记录；实时阶段的当前状态以 `LIVE_CAPTURE.md` 为准。

- 当前已完成离线 SAZ 批量分析；Fiddler MCP 准备返回启动超时，实时采集未接入，没有绕过受管连接器凭据。
- 尚未实现自动追踪客户端缓存/UI 刷新调用链与 AI 功能报告。
- 服务端额外两字节序号目前仅记录为 `extraSequence`，未将其推断语义用于自动关联。
- 本机持久化采用单实例文件快照；同一个 data 目录不要同时启动多个分析服务。
- 未执行游戏操作或向竞品服务器发送/重放任何请求；全部验收使用用户已有捕获数据。

## 版本控制范围

根目录仅修改 `settings.gradle` 注册 `protolens`。本模块新增文件已登记 SVN，未提交。模块的 `data/`、`.gradle/`、`build/`、`out/` 以及 web 的 `node_modules/`、`dist/` 已设置 SVN 忽略；不包含实际抓包、解码账号数据和完整竞品字典。

本次加入 SVN 的文件清单：

- `.gitignore`
- `AGENTS.md`
- `build.gradle`
- `README.md`
- `settings.gradle`
- `src/main/java/com/lashouwan/packetanalyzer/AnalyzerLimits.java`
- `src/main/java/com/lashouwan/packetanalyzer/CaptureStore.java`
- `src/main/java/com/lashouwan/packetanalyzer/CompactThriftDecoder.java`
- `src/main/java/com/lashouwan/packetanalyzer/GamePacketDecoder.java`
- `src/main/java/com/lashouwan/packetanalyzer/ProtoLensMain.java`
- `src/main/java/com/lashouwan/packetanalyzer/ProtocolCatalog.java`
- `src/main/java/com/lashouwan/packetanalyzer/SazImporter.java`
- `src/main/java/com/lashouwan/packetanalyzer/WebSocketStreamDecoder.java`
- `src/test/java/com/lashouwan/packetanalyzer/PacketDecoderTest.java`
- `src/test/java/com/lashouwan/packetanalyzer/SazImporterTest.java`
- `start-protolens.cmd`
- `tools/extract-catalog.mjs`
- `tools/extract-catalog.test.mjs`
- `VALIDATION.md`
- `web/index.html`
- `web/package.json`
- `web/package-lock.json`
- `web/public/favicon.svg`
- `web/src/App.vue`
- `web/src/JsonTree.vue`
- `web/src/main.ts`
- `web/src/splitGeometry.ts`
- `web/src/SplitPane.vue`
- `web/src/style.css`
- `web/src/types.ts`
- `web/tsconfig.json`
- `web/vite.config.ts`
