# ProtoLens

独立的本机抓包与协议分析工具：查看游戏发了什么请求、服务端推送了什么数据，并结合客户端源码交给 AI 分析。无需启动游戏后端。

## 功能

- **实时采集**：随工作台启动 mitmproxy 采集器，展示主游戏 WebSocket 的 CS / SC 消息。
- **协议解码**：从竞品客户端静态提取 Thrift Compact 字典，查看 JSON、字段定义、原始包体和解码状态。
- **检索过滤**：按协议名、编号、字段值、方向、状态及连接筛选，支持协议包含 / 排除、隐藏心跳和正逆序。
- **记录管理**：有界缓存、按需保存、删除记录、清空本次会话、标记操作区间、保存分析快照及导出 JSON；支持导入 Fiddler SAZ。
- **AI 分析**：内置只读 MCP，供 AI 查询抓包、字段定义及客户端源码，追踪请求、推送与页面刷新流程。
- **桌面运行**：安装版内置运行环境，支持自动打开页面、托盘退出、单实例唤起及覆盖升级保留数据；Windows 系统代理支持自动接管与恢复。

## 快速启动

## 下载

从 [GitHub Releases](https://github.com/sunycoder/protolens/releases) 下载已发布版本；构建过程见 [GitHub Actions](https://github.com/sunycoder/protolens/actions/workflows/release.yml)：

| 系统 | 安装包 | 便携版 |
| --- | --- | --- |
| Windows x64 | [下载 ProtoLens-latest.exe](https://github.com/sunycoder/protolens/releases/download/v0.1.3/ProtoLens-latest.exe) | [下载 Windows 便携版](https://github.com/sunycoder/protolens/releases/download/v0.1.3/ProtoLens-0.1.3-windows-x64.zip) |
| macOS | [下载 macOS 便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.3/ProtoLens-0.1.3-macos.tar.gz) | 同一文件 |
| Linux x64 | [下载 Linux 便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.3/ProtoLens-0.1.3-linux-x64.tar.gz) | 同一文件 |

三平台产物由 GitHub Actions 在对应系统运行器原生构建；Windows 提供安装器和 ZIP，macOS/Linux 提供原生 tar.gz 便携包。

**安装版（推荐）**：运行模块下的 `release/ProtoLens-latest.exe`，安装后打开快捷方式。无需安装 Java、Node 或采集器。便携版解压整个 ZIP 后运行 `ProtoLens/ProtoLens.exe`。

**源码版**：准备 JDK 21、Node.js 22.12+ 和 npm，在**仓库根目录**执行：

```powershell
.\protolens\start-protolens.cmd
```

安装版和便携版已内置采集器，可直接使用。源码版首次缺少采集器时自动下载固定版本、校验 SHA-256 并缓存，之后复用；无需手动下载、复制文件或安装 Python。首次自动准备需要联网，失败后检查网络并重启工作台即可重试，页面及离线导入仍可使用。

启动会自动拉起后端、采集器和前端并打开页面；也可在 IDE 运行 `com.lashouwan.protolens.ProtoLensMain`。

| 默认入口 | 地址 |
| --- | --- |
| 安装版 / 静态页面、后端 | `http://127.0.0.1:18766` |
| 源码开发页面（Vite 热更新） | `http://127.0.0.1:5174` |
| Windows 采集代理 | `127.0.0.1:18888` |
| MCP（Streamable HTTP） | `http://127.0.0.1:18766/mcp` |

## 使用流程

### 实时采集

1. **加载协议**：打开“设置 / 协议加载”，填写客户端根目录、任意分包目录或 `thrift_data.js` 完整路径，点击“查找协议文件”→选择版本→“加载协议并记住路径”。默认来源有效时启动自动加载；来源缺失时可启动后补填。
2. **首次信任证书**：启动采集器后，双击数据目录下的 `mitmproxy-conf/mitmproxy-ca-cert.cer` →“安装证书”→“当前用户”→“将所有的证书都放入下列存储”→“浏览”→“受信任的根证书颁发机构”→完成。源码版与安装版的数据目录不同，需信任各自生成的证书；只安装 `.cer`，不分享私钥。
3. **系统代理**：采集器就绪后自动尝试接管 Windows 系统代理。已有手动代理、PAC 或自动检测配置时，页面会先提示二次确认；选择“保留原代理”不会修改网络。也可用“接管系统代理 / 恢复原代理”按钮切换。
4. **开始采集**：页面点击“开始采集”，等待采集源接入。自动接管关闭时，可自行将 Windows 代理设为 `127.0.0.1:18888`。程序只管理系统代理，不保证识别 VPN，也不会关闭其 TUN 模式；是否暂停 TUN 由用户决定。
5. **操作并查看**：完全关闭并重新打开小游戏，再执行目标操作。搜索协议（例如 `CgAllianceTechDonate`），点击消息查看字段；CS 为客户端发送，SC 为服务端下发。点选消息暂停跟随，“回到最新”恢复跟随。
6. **保存与退出**：停止采集时自行选择是否保存，也可保存操作区间快照；未保存的记录退出后丢失。从托盘退出时自动恢复接管前的代理；其他软件已改过代理时保留它的新设置。旧版手动代理若已指向采集端口，确认接管后退出会关闭该遗留手动代理，无法恢复未知的更早 VPN 配置。关闭浏览器不会停止后端。

**清空会话**：点击筛选栏右侧“清空会话”，直接清空本次采集所有未保存记录的消息和标记。记录、当前选择、连接及解压上下文保持，后续消息继续写入原记录；历史导入、已保存记录及快照不受影响。消息原始编号继续递增，不重用。

默认采集 `bjxuejing.cn`、`tuyoo.com` 下以 `/websocket` 开头的 WebSocket 消息。地址变化时打开“设置 / 协议加载”→“采集目标”，编辑域名和路径，点击“保存采集目标”，按提示重启工作台并重进游戏。规则直接写入启动配置，页面会区分当前生效值与待生效值。切换协议字典后重新开始采集并重开游戏；已有记录保留自己的字典。

### 导入已有抓包

左下角“本地导入”选择 `.saz` 文件，或填写绝对路径后点击“从路径导入”。导出 SAZ 时选择**从握手开始的完整主游戏 WebSocket 会话**；离线导入无需开启系统代理。

### 让 AI 分析

1. 点击“AI 分析 / MCP”→“连接 AI”→“复制 MCP 连接配置”，在 AI 客户端添加 **Streamable HTTP** 服务，填写地址及复制配置中的 `Authorization` 请求头。工作台无需填写模型 API Key。
2. 关键操作前点击“开始标记操作”，操作后点击“结束并选取区间”，保存分析快照，避免实时消息被淘汰。
3. 选择记录，在 AI 分析面板填写操作背景，复制分析提问给已连接 MCP 的 AI。

MCP 提供记录列表、消息搜索、完整字段、前后文、协议定义与客户端源码查询；仅开放只读能力。凭证位于数据目录的 `mcp-access.key`，删除并重启后需重新配置 AI 连接。

## 配置与数据

| 运行方式 | 启动配置 | 默认数据目录 |
| --- | --- | --- |
| 源码版 | `protolens/config/analyzer.json` | `protolens/data/` |
| Windows 安装版 / 便携版 | `%LOCALAPPDATA%/ProtoLens/analyzer.json` | `%LOCALAPPDATA%/ProtoLens/data/` |

安装版首次创建用户配置，升级不覆盖。页面设置和启动日志显示实际配置路径、数据目录与端口。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `serverPort` | `18766` | 后端、静态页面及 MCP 端口 |
| `frontendPort` | `5174` | 源码开发页面端口 |
| `collectorPort` | `18888` | 采集代理端口，需与 Windows 代理一致 |
| `frontendMode` | `auto` | 源码使用 `dev`，发行包使用 `static` |
| `openBrowser` | `true` | 启动后自动打开页面 |
| `autoStartCollector` | `true` | 随后端启动采集器，缺失时自动准备 |
| `autoSystemProxy` | `true` | Windows 采集器就绪后自动尝试接管；已有配置须确认，设为 `false` 可手动接管 |
| `captureHosts` | `["bjxuejing.cn", "tuyoo.com"]` | 采集域名或 IPv4 数组，域名包含子域名；不填协议和端口 |
| `capturePathPrefixes` | `["/websocket"]` | 路径前缀数组，区分大小写；`["/"]` 匹配目标域名的全部 WebSocket |
| `dataDirectory` | `data` | 源码版相对模块目录；发行包相对配置文件目录 |
| `competitorPath` | `D:/work/20261008/slgProject` | 默认协议目录或文件，改成本机实际路径 |
| `protocolFile` | 空 | 多个候选时指定相对文件路径；空值仅自动选择唯一候选 |
| `protocolAutoLoad` | `true` | 启动时自动加载协议 |
| `maxMessages` | `10000` | 每条实时连接的消息缓存上限 |
| `maxCacheMiB` | `64` | 每条连接的序列化消息容量上限，单位 MiB |

修改端口、启动开关、采集域名或路径后重启；协议来源和缓存预算可在页面保存，缓存预算在停止采集后修改，新连接生效。达到任一缓存上限淘汰最旧消息；临时记录最多 32 份，可自行保存或删除。

启动配置优先级：**默认值 < JSON 配置 < 显式 JVM 参数 < 命令行参数**。页面选择的协议来源与缓存预算另存 `data/settings.json`，优先于配置默认值。路径在 JSON 中推荐使用 `/`。

```powershell
# 指定其他启动配置（仓库根目录）
.\gradlew.bat -p protolens run "-PanalyzerConfig=D:/config/analyzer.json"
```

IDE 程序参数或发行包 EXE 可使用 `--config <文件绝对路径>`，或用 `--data-dir <目录绝对路径>` 隔离数据。抓包在数据目录的 `captures/`，后端日志为 `logs/analyzer.log`，采集器日志为 `logs/collector.log`，协议提取日志为 `logs/catalog-extract.log`。代理恢复备份位于用户应用目录的 `system-proxy/proxy-lease.json`，异常退出后下次自动启动会核验并恢复；不要手动复制此备份。抓包、本机数据、证书及凭证不加入版本控制。

## 常用命令

以下命令均在**仓库根目录**执行；只处理 `protolens` 模块。

| 用途 | 命令 |
| --- | --- |
| 启动 | `.\gradlew.bat -p protolens run` |
| 后端测试 | `.\gradlew.bat -p protolens test` |
| 构建前端 | `.\gradlew.bat -p protolens webBuild` |
| 格式化 Java 与前端 | `.\gradlew.bat -p protolens format` |
| 只检查格式 | `.\gradlew.bat -p protolens checkFormat` |
| 手动提取协议字典 | `.\gradlew.bat -p protolens extractCatalog "-PcompetitorDir=D:/client/slgProject"` |
| 打包安装版 | `.\gradlew.bat -p protolens packageInstaller` |
| 打包便携版 | `.\gradlew.bat -p protolens packagePortable` |
| 检查分发内容 | `.\gradlew.bat -p protolens verifyDistribution` |

### 打包参数与产物

构建需 JDK 21、Node/npm；缺少默认 mitmdump 时会自动准备；Windows 安装器另需 **WiX 3**，便携版无需 WiX。Node 默认从 PATH 查找，mitmdump 默认取 `data/mitmproxy/mitmdump.exe`。工具不在默认位置时：

```powershell
.\gradlew.bat -p protolens packageInstaller `
  "-PanalyzerWixDir=C:/Program Files (x86)/WiX Toolset v3.14/bin" `
  "-PanalyzerNodeExecutable=D:/dev/Node/node.exe" `
  "-PanalyzerMitmdumpExecutable=D:/tools/mitmproxy/mitmdump.exe" `
  -PanalyzerVersion=0.1.3
```

| 产物 | 模块内路径 |
| --- | --- |
| 最新 Windows 安装包 | `release/ProtoLens-latest.exe` |
| Windows 便携 ZIP | `build/distributions/ProtoLens-<版本>-windows-x64.zip` |
| 完整运行目录 | `build/app-image/ProtoLens/` |

WiX 已在 PATH 时可省略 `analyzerWixDir`；版本默认取 `src/main/resources/analyzer-version.properties`。离线打包可传 `-PanalyzerNodeLicense=<对应版本LICENSE>`、`-PanalyzerMitmproxyLicense=<对应版本LICENSE>`。安装包不包含本机抓包、字典或证书；覆盖升级前先从托盘退出。其他平台须在对应系统原生构建，当前已验证 Windows x64。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 开启代理后游戏无法联网 | 先点击“恢复原代理”，检查采集器及证书，再接管、重进游戏；使用 TUN 的 VPN 可能需要自行调整。 |
| 提示采集器未安装 / 代理端口被占用 | 源码版首次自动准备需联网，查看 `logs/collector.log` 和后端日志；端口占用时先关闭代理及旧采集器，再重启工作台。 |
| 有采集源但没有游戏消息 | 核对代理地址与端口，确认已开始采集，再完全关闭并重新打开小游戏。 |
| 协议数量为 0 / 部分解码 / 未映射 | 在设置中加载对应客户端版本的协议，查看消息详情中的原因；旧 SAZ 需重新导入才能使用新字典。 |

更名前已安装版本的数据目录会继续复用；全新安装默认使用用户目录下的 `ProtoLens`。源码版数据仍在本模块的 `data/`。
