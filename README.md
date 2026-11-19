# ProtoLens

独立的本机抓包与协议分析工具：查看游戏发了什么请求、服务端推送了什么数据，并结合客户端源码交给 AI 分析。无需启动游戏后端。

## 功能

- **实时采集**：随工作台启动 mitmproxy 采集器，展示主游戏 WebSocket 的 CS / SC 消息。
- **协议解码**：从竞品客户端静态提取 Thrift Compact 字典，查看 JSON、字段定义、原始包体和解码状态。
- **检索过滤**：按协议名、协议 ID、消息序号、字段值、方向、状态及连接筛选；生效条件显示为可移除标签，支持全部重置。
- **上下文与字段分析**：搜索结果可跳转上下文，自动滚动并整行高亮目标消息；可返回原搜索条件、页码、位置和选中行。上一条 / 下一条支持跨页，消息体命中字段自动展开并高亮；支持固定一条消息，与另一条对比字段变化。
- **记录管理**：有界缓存、按需保存、删除记录、清空本次会话、标记操作区间、保存分析快照及导出 JSON；支持导入 Fiddler SAZ。
- **AI 分析**：内置只读 MCP，供 AI 查询抓包、字段定义及客户端源码，追踪请求、推送与页面刷新流程。
- **桌面运行**：安装版内置运行环境，支持自动打开页面、托盘退出、单实例唤起及覆盖升级保留数据；Windows 系统代理支持自动接管与恢复。

## 下载与启动

从 [GitHub Releases](https://github.com/sunycoder/protolens/releases) 下载已发布版本；构建过程见 [GitHub Actions](https://github.com/sunycoder/protolens/actions/workflows/release.yml)：

| 系统 | 安装包 | 便携版 |
| --- | --- | --- |
| Windows x64 | [下载 ProtoLens-latest.exe](https://github.com/sunycoder/protolens/releases/download/v0.1.5/ProtoLens-latest.exe) | [下载 Windows ZIP](https://github.com/sunycoder/protolens/releases/download/v0.1.5/ProtoLens-0.1.5-windows-x64.zip) |
| macOS Apple Silicon | 新增 `macos-arm64.dmg` 构建，发布后从 Releases 下载 | 同架构 `.app` 的 tar.gz |
| macOS Intel | 新增 `macos-x64.dmg` 构建，发布后从 Releases 下载 | 同架构 `.app` 的 tar.gz |
| Linux x64 | [下载 Linux tar.gz](https://github.com/sunycoder/protolens/releases/download/v0.1.5/ProtoLens-0.1.5-linux-x64.tar.gz) | 同一文件 |

当前发布版本：`v0.1.5`。上表 macOS DMG 为本次新增流程的产物，尚未发布；旧版 macOS tar.gz 仍是需要 Java 21 的命令行启动包，不要与新的原生应用包混用。

**Windows 安装版**：运行下载的 `ProtoLens-latest.exe`，安装后打开快捷方式；便携版解压整个 ZIP 后运行 `ProtoLens/ProtoLens.exe`，无需另装 Java、Node 或采集器。

**macOS DMG 安装版（新流程）**：在“关于本机”确认芯片：Apple M 系列选择 `macos-arm64.dmg`，Intel 选择 `macos-x64.dmg`。双击 DMG，将 `ProtoLens.app` 拖入 `Applications`，弹出磁盘映像，再从“应用程序”双击 ProtoLens。应用会启动本机服务并自动打开浏览器中的工作台，不需要安装 Java、Node、Python 或在终端执行命令。实时采集仍需按下文信任证书并配置代理。

**macOS 便携版（新流程）**：解压对应架构的 tar.gz，双击完整的 `ProtoLens.app`；运行环境同样内置，不要将启动程序单独移出 `.app`。

**首次打开与系统安全提示**：当前流程没有配置 Apple Developer ID 签名和公证。下载后的应用可能被 macOS 拦截；确认来自本仓库后，在“系统设置 → 隐私与安全性”使用系统提供的“仍要打开”入口（系统版本不同，名称可能略有差异），不需要关闭系统安全检查或执行终端命令。内置 Java 与 Apple 签名、公证是两件事，CI 启动检查不能证明已通过 Gatekeeper 公证。

**Linux 便携版**：解压整个 tar.gz，在解压目录执行 `./ProtoLens/bin/ProtoLens`。桌面打开和托盘功能取决于桌面环境，无桌面时查看日志给出的本机页面地址。

**源码版**：准备 JDK 21、Node.js 22.12+ 和 npm，在仓库根目录执行：

| 系统 | 启动命令 |
| --- | --- |
| Windows（PowerShell / CMD） | `.\start-protolens.cmd` |
| macOS（终端） | `sh ./start-protolens.sh` |
| Linux（终端） | `sh ./start-protolens.sh` |

脚本按自身位置定位仓库，支持带空格的目录。需要启动前提取字典时，Windows 使用 `.\start-protolens.cmd "D:/client/project"`；macOS / Linux 使用 `sh ./start-protolens.sh "/实际路径/client project"`。提取失败时停止启动。无需单独运行 `collect-protolens.cmd` / `collect-protolens.sh`，它们仅保留为兼容入口。

安装版和便携版已内置采集器，可直接使用。源码版首次缺少采集器时自动下载固定版本、校验 SHA-256 并缓存，之后复用；无需手动下载、复制文件或安装 Python。首次自动准备需要联网，失败后检查网络并重启工作台即可重试，页面及离线导入仍可使用。

启动会自动拉起后端、采集器和前端并打开页面；也可在 IDE 运行 `com.lashouwan.protolens.ProtoLensMain`。

| 默认入口 | 地址 |
| --- | --- |
| 安装版 / 静态页面、后端 | `http://127.0.0.1:18766` |
| 源码开发页面（Vite 热更新） | `http://127.0.0.1:5174` |
| HTTP / HTTPS 采集代理（各平台） | `127.0.0.1:18888` |
| MCP（Streamable HTTP） | `http://127.0.0.1:18766/mcp` |

## 使用流程

### 实时采集

1. **加载协议**：打开“设置 / 协议加载”，填写客户端根目录、任意分包目录或 `thrift_data.js` 完整路径，点击“查找协议文件”→选择版本→“加载协议并记住路径”。默认来源有效时启动自动加载；来源缺失时可启动后补填。
2. **首次信任证书**：在“连接与诊断”查看实际公开证书路径，默认位于数据目录的 `mitmproxy-conf/mitmproxy-ca-cert.cer`，按下表导入。源码版与发行包的数据目录不同；不要导入或分享同目录内的私钥。
3. **配置代理**：按下表为游戏使用的网络配置 HTTP 和 HTTPS 代理，地址 `127.0.0.1`，端口以页面为准，默认 `18888`。自动代理接管目前仅支持 Windows。
4. **开始采集**：页面点击“开始采集”。程序不保证识别 VPN，也不会关闭其 TUN 模式，是否暂停 TUN 由用户决定。
5. **操作并查看**：完全关闭并重新打开小游戏，再执行目标操作。搜索协议（例如 `CgAllianceTechDonate`），点击消息查看字段；CS 为客户端发送，SC 为服务端下发。点选消息暂停跟随，“回到最新”恢复跟随。
6. **保存与退出**：停止采集时自行选择是否保存，也可保存操作区间快照；未保存的记录退出后丢失。Windows 从托盘退出时自动恢复接管前的代理；其他软件已改过代理时保留它的新设置。旧版手动代理若已指向采集端口，确认接管后退出会关闭该遗留手动代理，无法恢复未知的更早 VPN 配置。关闭浏览器不会停止后端。

| 系统 | 信任公开 CA 证书 | 配置与恢复代理 |
| --- | --- | --- |
| Windows | 双击公开证书 → 安装证书 → 当前用户 → 受信任的根证书颁发机构 | 采集器就绪后可自动尝试接管，已有代理时先确认；也可点击“接管系统代理 / 恢复原代理”。已接管的设置在退出时恢复。 |
| macOS | 在“钥匙串访问”导入证书，双击证书 → 信任，按提示设置信任并完成系统验证 | 系统设置 → 网络 → 当前网络 → 详细信息 → 代理，配置网页代理（HTTP）和安全网页代理（HTTPS）；退出后手动恢复原配置。系统版本不同，菜单名称可能略有差异。 |
| Linux | 使用发行版或客户端的证书管理工具导入公开 CA | 在桌面网络设置或游戏客户端中配置 HTTP / HTTPS 代理；不同发行版入口不同，退出后手动恢复原配置。 |

客户端使用独立证书库或不遵循桌面代理时，需在客户端单独配置。配置后重开游戏，确认页面收到的帧数增加；不能仅凭代理设置成功就认为已经采集到游戏消息。

**清空会话**：点击实时采集区的“清空本次采集消息”，确认后清空本次采集所有未保存记录的消息和标记。记录、当前选择、连接及解压上下文保持，后续消息继续写入原记录；历史导入、已保存记录及快照不受影响。消息序号继续递增，不重用。

**搜索与上下文**：协议 ID 表示协议类型；消息序号表示该条消息在抓包记录中的原始顺序，两者分别查询。点击搜索结果的协议名或“上下文”，可查看附近消息；目标行会滚动到可见位置并整行高亮。“返回搜索结果”恢复原查询、筛选、排序、页码和滚动位置。筛选会暂停列表跟随，后台采集继续。

**阅读布局**：默认收起记录侧栏，点击“记录 / 导入”展开。自动布局依据数据区实际宽高及可见列宽度，选择右侧、底部或浮层详情；展开侧栏或短窗口空间不足时使用浮层，保持列表高度。可在“表格设置”选择布局、显示列或“恢复默认布局”；恢复布局保留消息、查询和选中行。“收起详情”保留选中消息，“专注列表”临时隐藏顶部操作区和详情。上下方向键连续阅读，Esc 收起详情或退出专注；输入框、弹窗及分隔条操作不受这些快捷键影响。

**查询状态与恢复**：条件标签、计数、页码和列表在查询成功后同时更新；等待、失败或取消时保留上次成功结果，底部分页区显示该结果的排序和取数时间。列表不因查询被蒙层遮挡，可使用状态提示中的取消或重试。没有匹配结果时，可检查筛选或清除全部筛选；超过三个条件默认折叠展示，其余条件可展开逐个移除。

**上下文范围**：普通列表点击协议名查看详情；搜索结果点击协议名定位到上下文，并保留排序及心跳条件。行操作菜单可切换“当前连接上下文 / 全部连接上下文”。跳转成功后才更新显示条件，失败时保留原结果。“返回搜索结果”恢复原查询现场。

**详情与长字段**：切换消息时先显示目标消息序号，新详情加载完成前隐藏旧消息体；失败后可单独重试。较长字段值默认显示前 240 字符，可按需展开更多内容，或点击“复制值”复制完整原始值。行操作菜单在悬停、选中或键盘聚焦时显示，低频列可在表格设置中关闭。

**字段定位与对比**：搜索字段名或实际值后，列表优先显示命中字段摘要；选中消息后，在命中字段列表中点击路径，定位到 JSON 树中的对应字段。将一条消息“设为对比基准”，再选另一条并点击“对比变化”，查看字段路径、基准值、当前值及新增 / 移除 / 修改。数组按位置比较；搜索与对比均有扫描和展示上限，达到上限时明确提示，不自动推断请求响应关系。

默认采集 `bjxuejing.cn`、`tuyoo.com` 下以 `/websocket` 开头的 WebSocket 消息。地址变化时打开“设置 / 协议加载”→“采集目标”，编辑域名和路径，点击“保存采集目标”，按提示重启工作台并重进游戏。规则直接写入启动配置，页面会区分当前生效值与待生效值。切换协议字典后重新开始采集并重开游戏；已有记录保留自己的字典。

### 扩展功能：导入已有抓包

点击“记录 / 导入”，在“本地导入”选择 `.saz` 文件，或填写绝对路径后点击“从路径导入”。导出 SAZ 时选择**从握手开始的完整主游戏 WebSocket 会话**；离线导入无需开启系统代理。页面“更多 → SAZ 导入说明”只介绍这项扩展功能；实时采集使用顶部“接入步骤”。

### 让 AI 分析

1. 点击“AI 分析 / MCP”→“连接 AI”→“复制 MCP 连接配置”，在 AI 客户端添加 **Streamable HTTP** 服务，填写地址及复制配置中的 `Authorization` 请求头。工作台无需填写模型 API Key。
2. 关键操作前点击“开始标记操作”，操作后点击“结束并选取区间”，保存分析快照，避免实时消息被淘汰。
3. 选择记录，在 AI 分析面板填写操作背景，复制分析提问给已连接 MCP 的 AI。

MCP 提供记录列表、消息搜索、完整字段、前后文、协议定义与客户端源码查询；仅开放只读能力。凭证位于数据目录的 `mcp-access.key`，删除并重启后需重新配置 AI 连接。

## 配置与数据

| 运行方式 | 启动配置 | 默认数据目录 |
| --- | --- | --- |
| 源码版 | `config/analyzer.json` | `data/` |
| Windows 安装版 / 便携版 | `%LOCALAPPDATA%/ProtoLens/analyzer.json` | `%LOCALAPPDATA%/ProtoLens/data/` |
| macOS 发行包 | `~/Library/Application Support/ProtoLens/analyzer.json` | `~/Library/Application Support/ProtoLens/data/` |
| Linux 发行包 | `~/.local/share/ProtoLens/analyzer.json` | `~/.local/share/ProtoLens/data/` |

Linux 设置了绝对路径的 `XDG_DATA_HOME` 时使用其下的 `ProtoLens`；更名前的用户数据目录存在时继续复用。实际位置以页面和日志为准。

安装版首次创建用户配置，升级不覆盖。页面设置和启动日志显示实际配置路径、数据目录与端口。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `serverPort` | `18766` | 后端、静态页面及 MCP 端口 |
| `frontendPort` | `5174` | 源码开发页面端口 |
| `collectorPort` | `18888` | 采集代理端口，需与系统或客户端的 HTTP / HTTPS 代理一致 |
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

启动配置优先级：**默认值 < JSON 配置 < 显式 JVM 参数 < 命令行参数**。页面选择的协议来源与缓存预算另存 `data/settings.json`，优先于配置默认值。路径在 JSON 中推荐使用 `/`。 Windows 可填写 `D:/client/project`；macOS 可填写 `/Users/用户名/项目 目录`；Linux 可填写 `/home/用户名/project`。页面和 JSON 使用工作台所在电脑的实际完整路径，不自动展开 `~`、`$HOME` 或 `file://`；空格、中文无需手动转义。终端命令传入带空格的路径时，使用引号包住整个参数。

```powershell
# 指定其他启动配置（仓库根目录）
.\gradlew.bat run "-PanalyzerConfig=D:/config/analyzer.json"
```

macOS / Linux：`sh ./gradlew run "-PanalyzerConfig=/实际路径/analyzer.json"`。

IDE 程序参数或发行包启动器 可使用 `--config <文件绝对路径>`，或用 `--data-dir <目录绝对路径>` 隔离数据。抓包在数据目录的 `captures/`，后端日志为 `logs/analyzer.log`，采集器日志为 `logs/collector.log`，协议提取日志为 `logs/catalog-extract.log`。代理恢复备份位于用户应用目录的 `system-proxy/proxy-lease.json`，异常退出后下次自动启动会核验并恢复；不要手动复制此备份。抓包、本机数据、证书及凭证不加入版本控制。

## 常用命令

以下命令均在**仓库根目录**执行。

| 用途 | Windows | macOS / Linux |
| --- | --- | --- |
| 一键启动 | `.\start-protolens.cmd` | `sh ./start-protolens.sh` |
| 直接运行 | `.\gradlew.bat run` | `sh ./gradlew run` |
| 后端测试 | `.\gradlew.bat test` | `sh ./gradlew test` |
| 构建前端 | `.\gradlew.bat webBuild` | `sh ./gradlew webBuild` |
| 格式化 | `.\gradlew.bat format` | `sh ./gradlew format` |
| 格式检查 | `.\gradlew.bat checkFormat` | `sh ./gradlew checkFormat` |
| 提取协议字典 | `.\gradlew.bat extractCatalog "-PcompetitorDir=D:/client/project"` | `sh ./gradlew extractCatalog "-PcompetitorDir=/实际路径/client project"` |
| 打包本机安装版 | `.\gradlew.bat packageInstaller` | `sh ./gradlew packageInstaller` |
| 打包本机便携版 | `.\gradlew.bat packagePortable` | `sh ./gradlew packagePortable` |
| 检查分发内容 | `.\gradlew.bat verifyDistribution` | `sh ./gradlew verifyDistribution` |
| 单独构建前端（web 目录） | `npm.cmd run build` | `npm run build` |
| Node 工具测试 | `node --experimental-strip-types --test tools/*.test.mjs` | `node --experimental-strip-types --test tools/*.test.mjs` |

macOS / Linux 使用 `sh` 运行脚本，不依赖下载或拷贝后是否保留可执行权限。打包必须在目标系统及架构原生执行，不能把 Windows 输出当成 macOS / Linux 包。

### 打包参数与产物

构建需 JDK 21、Node/npm；缺少默认 mitmdump 时会自动准备；Windows 安装器另需 **WiX 3**，便携版无需 WiX。Node 默认从 PATH 查找，mitmdump 在 Windows 默认取 `data/mitmproxy/mitmdump.exe`，在 macOS / Linux 默认取 `data/mitmproxy/mitmdump`。工具不在默认位置时：

```powershell
.\gradlew.bat packageInstaller `
  "-PanalyzerWixDir=C:/Program Files (x86)/WiX Toolset v3.14/bin" `
  "-PanalyzerNodeExecutable=D:/dev/Node/node.exe" `
  "-PanalyzerMitmdumpExecutable=D:/tools/mitmproxy/mitmdump.exe" `
  -PanalyzerVersion=0.1.5
```

macOS / Linux 使用同名 Gradle 属性和本机程序路径，使用 POSIX 换行符，例如：

```sh
sh ./gradlew packagePortable \
  "-PanalyzerNodeExecutable=/实际路径/node" \
  "-PanalyzerMitmdumpExecutable=/实际路径/mitmdump"
```

Windows 原生安装器需要 WiX 3；macOS DMG 使用系统 `hdiutil` 和 Xcode Command Line Tools 的 `xcrun`。macOS 发布流程在 Apple Silicon、Intel 构建机分别执行 `packageInstaller packagePortable`，通过 jpackage 内置 Java 21，并携带同架构 Node 和独立采集器。源码 Gradle Wrapper 与 CI 均使用 Gradle 8.14.5。

macOS 的 jpackage 不接受首段为 0 的构建版本，因此内部 `CFBundleVersion` 将对外版本主段加 1（例如 `0.1.5 → 1.1.5`，`1.0.0 → 2.0.0`），保持升级顺序；自定义 Info.plist 在签名前保留原始 `CFBundleShortVersionString`。Finder、应用界面、DMG 文件名和 Release 标签仍使用对外版本，安装验收同时核对两种版本。

macOS 本机构建后可运行 `node tools/verify-macos-package.mjs build/installer/ProtoLens-<版本>-macos-<架构>.dmg macos-<架构>` 验收，架构取 `arm64` 或 `x64`。脚本挂载 DMG，将应用复制到包含空格、中文的独立目录，检查 CPU 与内置运行时，分别通过原生启动器和 Finder 使用的 Launch Services 启动，验证静态页面、用户数据目录和采集器；测试使用独立配置，不接管系统代理。`.github/workflows/macos-package.yml` 可手动运行，相关 PR / 主分支改动也会触发两种架构验收，成功后保留安装包供下载。

发布工作流在两个架构均通过以上验收后才上传 GitHub Release；手动执行 `release.yml` 仅构建、验收并保存 Actions 产物，推送与应用版本一致的 `v<版本>` 标签才发布。macOS 使用包含版本和架构的独立文件名，更新检查优先推荐同架构 DMG，避免被压缩包或另一架构抢占。

| 产物 | 模块内路径 |
| --- | --- |
| 最新 Windows 安装包 | `release/ProtoLens-latest.exe` |
| Windows 便携 ZIP | `build/distributions/ProtoLens-<版本>-windows-x64.zip` |
| macOS Apple Silicon DMG | `build/installer/ProtoLens-<版本>-macos-arm64.dmg` |
| macOS Intel DMG | `build/installer/ProtoLens-<版本>-macos-x64.dmg` |
| macOS 原生便携包 | `build/distributions/ProtoLens-<版本>-macos-<架构>.tar.gz` |
| macOS 完整应用 | `build/app-image/ProtoLens.app/` |
| Windows / Linux 完整运行目录 | `build/app-image/ProtoLens/` |

WiX 已在 PATH 时可省略 `analyzerWixDir`；版本默认取 `src/main/resources/analyzer-version.properties`。离线打包可传 `-PanalyzerNodeLicense=<对应版本LICENSE>`、`-PanalyzerMitmproxyLicense=<对应版本LICENSE>`。安装包不包含本机抓包、字典或证书；覆盖升级前先从托盘退出。平台实机验收与 CI 构建检查应分开记录；在 Windows 做脚本合同检查不能替代 macOS / Linux 实机启动。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 开启代理后游戏无法联网 | 先点击“恢复原代理”，检查采集器及证书，再接管、重进游戏；使用 TUN 的 VPN 可能需要自行调整。 |
| 提示采集器未安装 / 代理端口被占用 | 源码版首次自动准备需联网，查看 `logs/collector.log` 和后端日志；端口占用时先关闭代理及旧采集器，再重启工作台。 |
| 有采集源但没有游戏消息 | 核对代理地址与端口，确认已开始采集，再完全关闭并重新打开小游戏。 |
| 协议数量为 0 / 部分解码 / 未映射 | 在设置中加载对应客户端版本的协议，查看消息详情中的原因；旧 SAZ 需重新导入才能使用新字典。 |

更名前已安装版本的数据目录会继续复用；全新安装默认使用用户目录下的 `ProtoLens`。源码版数据仍在本模块的 `data/`。
