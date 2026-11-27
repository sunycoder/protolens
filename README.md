# ProtoLens

游戏协议采集与分析工作台。当前版本 **v0.1.9**，公开下载无需 GitHub 登录或源码访问凭据。

| 系统 | 安装包 | 便携包 |
| --- | --- | --- |
| Windows x64 | [下载安装包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-latest.exe) | [下载便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-windows-x64.zip) |
| macOS Apple Silicon | [下载安装包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-macos-arm64.dmg) | [下载便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-macos-arm64.tar.gz) |
| macOS Intel | [下载安装包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-macos-x64.dmg) | [下载便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-macos-x64.tar.gz) |
| Linux x64 | — | [下载便携包](https://github.com/sunycoder/protolens/releases/download/v0.1.9/ProtoLens-0.1.9-linux-x64.tar.gz) |

校验文件：[SHA256SUMS.txt](https://github.com/sunycoder/protolens/releases/download/v0.1.9/SHA256SUMS.txt) · [完整发布清单](https://github.com/sunycoder/protolens/releases/download/v0.1.9/release-manifest.json)。升级前请保存临时抓包并退出旧工作台。历史版本下载地址继续保留。

支持 HTTP、WebSocket 采集、协议查询、消息对比、多项目与环境、区间标记和只读 MCP 分析。安装或解压运行后，在“项目与环境”选择解码方式、协议来源和采集目标。

## 源码与构建

此仓库只保存可信发行工作流、发布脚本和公开下载说明。完整源码与原始历史独立保存在私有仓库。

维护者在 Actions → **ProtoLens public distribution** → **Run workflow** 中填写完整源码提交 SHA、与源码一致的版本号，选择 `verify` 验证或 `release` 正式发布。工作流在原生 Windows、macOS 双架构及 Linux 运行器构建，校验全部预期文件后才公开 Release。

`SOURCE_REPOSITORY` 为源码仓库变量，`SOURCE_READ_TOKEN` 仅用于读取该仓库的 Contents，并保存在 Actions Secret；到期后由维护者续期。禁止向本仓库推送业务源码或源码历史。其他电脑的源码克隆也应将 origin 改为私有源码仓库。
