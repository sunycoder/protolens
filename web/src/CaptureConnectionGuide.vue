<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api } from "./types";
import {
  guidePlatform,
  guidePort,
  linuxCertificateCommands,
  platformLabels,
  type GuidePlatform,
} from "./captureGuide";

interface CollectorInfo {
  platform?: string;
  state: string;
  message: string;
  port?: number;
  proxyPort?: number;
  proxyHost?: string;
  certificateFile?: string;
  certificateAvailable?: boolean;
}
const props = defineProps<{
  modelValue: boolean;
  live: {
    enabled: boolean;
    receivedFrames: number;
    source: string;
    connections: { messages: number }[];
  };
  proxy?: { supported: boolean; managed: boolean; existingIsTarget: boolean };
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  refresh: [];
}>();
const meta = ref<CollectorInfo>({ state: "UNKNOWN", message: "" });
const platform = ref<GuidePlatform>();
const loading = ref(false);
const error = ref("");
const linuxFamily = ref<"debian" | "fedora">("debian");
const downloadedPath = ref("");
let requestVersion = 0;
const hostPlatform = computed(() => guidePlatform(meta.value.platform));
const port = computed(() => guidePort(meta.value));
const host = computed(() => meta.value.proxyHost || "127.0.0.1");
const commands = computed(() =>
  linuxCertificateCommands(downloadedPath.value, linuxFamily.value),
);
const next = computed(() => {
  if (loading.value) return "正在读取当前工作台的采集配置…";
  if (error.value) return "接入信息读取失败，请重试；不要按未知端口配置代理。";
  if (meta.value.state !== "RUNNING")
    return meta.value.message || "先在连接与诊断确认采集器已运行，再配置代理。";
  if (!props.live.enabled)
    return "采集器已运行，完成证书与代理设置后点击顶部“开始采集”。";
  if (props.live.connections.some((item) => item.messages > 0))
    return "已收到游戏消息。可关闭说明并查看协议；未知或部分解码时核对协议字典。";
  if (props.live.receivedFrames > 0)
    return "已收到流量。尚无游戏消息时核对采集目标、连接及协议字典。";
  return "采集已开启，等待游戏流量：确认代理后完全退出并重新打开游戏。";
});
async function refresh(selectPlatform = false) {
  const version = ++requestVersion;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<CollectorInfo>("/api/collector");
    if (version !== requestVersion || !props.modelValue) return;
    meta.value = result;
    if (selectPlatform || !platform.value)
      platform.value = guidePlatform(result.platform);
  } catch (cause) {
    if (version === requestVersion) {
      error.value = String(cause);
      meta.value = { state: "UNKNOWN", message: "" };
    }
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success("已复制");
  } catch {
    ElMessage.error("复制失败，请手动选择文本复制");
  }
}
watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      emit("refresh");
      void refresh(true);
    } else requestVersion++;
  },
  { immediate: true },
);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="实时采集接入"
    width="min(820px, 94vw)"
    class="capture-connection-guide"
    align-center
    destroy-on-close
  >
    <div class="guide-status" role="status">
      <strong>当前下一步</strong><span>{{ next }}</span>
      <el-button
        text
        size="small"
        :loading="loading"
        @click="
          emit('refresh');
          refresh();
        "
        >刷新状态</el-button
      >
    </div>
    <p v-if="error" class="error-bar">{{ error }}</p>
    <p class="muted">
      工作台系统：{{
        hostPlatform
          ? platformLabels[hostPlatform]
          : "尚未识别，请手动选择下方系统"
      }}。按实际运行游戏和工作台的系统查看步骤；首次配置完成且证书未变时，可跳过证书安装。
    </p>
    <el-radio-group
      v-model="platform"
      class="guide-platforms"
      aria-label="查看操作系统步骤"
    >
      <el-radio-button value="windows">Windows</el-radio-button>
      <el-radio-button value="macos">macOS</el-radio-button>
      <el-radio-button value="linux">Linux</el-radio-button>
    </el-radio-group>
    <div v-if="platform" class="connection-steps">
      <p v-if="hostPlatform && platform !== hostPlatform" class="warning-bar">
        正在查看 {{ platformLabels[platform] }} 说明；当前证书路径属于
        {{ platformLabels[hostPlatform] }}
        工作台。切到另一台电脑运行时，应使用那台工作台显示的证书和端口。
      </p>
      <section>
        <h3>1. 确认采集器和协议</h3>
        <p>
          在“连接与诊断”确认采集器正在运行；在“设置 /
          协议加载”加载与当前游戏版本对应的字典。字典未加载仍可能收到原始消息，但不能将“未映射”当作已解码。
        </p>
      </section>
      <section>
        <h3>2. 安装并信任公开证书（首次配置）</h3>
        <div class="certificate-actions">
          <a
            v-if="meta.certificateAvailable"
            class="guide-download"
            href="/api/collector/certificate"
            download="protolens-ca.crt"
            >下载公开证书</a
          >
          <span v-else class="muted"
            >证书尚未就绪：确认采集器已运行后刷新状态。</span
          >
        </div>
        <details v-if="meta.certificateFile" class="guide-path">
          <summary>查看工作台上的证书路径</summary>
          <el-input
            :model-value="meta.certificateFile"
            readonly
            aria-label="当前公开证书路径"
            ><template #append
              ><el-button @click="copy(meta.certificateFile!)"
                >复制路径</el-button
              ></template
            ></el-input
          >
        </details>
        <ol v-if="platform === 'windows'">
          <li>打开下载的 <code>protolens-ca.crt</code>，选择“安装证书”。</li>
          <li>
            选择“当前用户” → “将所有的证书都放入下列存储” → “浏览” →
            “受信任的根证书颁发机构”。
          </li>
          <li>
            完成导入并确认系统提示。已安装但仍报证书错误时，核对当前工作台是否更换过数据目录或证书。
          </li>
        </ol>
        <ol v-else-if="platform === 'macos'">
          <li>
            可使用顶部“接管系统代理”，按 macOS 提示授权，接管当前联网的 Wi-Fi
            或以太网。
            “恢复原代理”或正常退出时尝试恢复原网络服务的设置。本次运行复用首次网络授权，系统撤销或授权失效时需重新确认。
            切换网络后先恢复再重新接管；认证代理或无法识别网络时，请使用下方手动方式。
          </li>
          <li>
            通过 Spotlight 搜索打开“钥匙串访问”，选择“系统”钥匙串；使用“文件 →
            导入项目”导入下载的证书。
          </li>
          <li>
            找到导入的 mitmproxy
            证书，双击展开“信任”，将“使用此证书时”设为“始终信任”。
          </li>
          <li>
            关闭证书窗口，按系统提示完成身份验证。回到钥匙串核对信任设置已保存。
          </li>
        </ol>
        <template v-else>
          <p>
            Linux
            的证书信任与发行版、客户端证书库有关。先选择发行版，再填写下载文件的实际绝对路径，生成对应命令：
          </p>
          <el-radio-group v-model="linuxFamily" size="small"
            ><el-radio-button value="debian">Debian / Ubuntu</el-radio-button
            ><el-radio-button value="fedora"
              >Fedora / RHEL</el-radio-button
            ></el-radio-group
          >
          <el-input
            v-model="downloadedPath"
            placeholder="例如 /home/你的用户名/Downloads/protolens-ca.crt"
            aria-label="Linux 证书绝对路径"
            clearable
          />
          <div v-if="commands" class="guide-command">
            <pre>{{ commands }}</pre>
            <el-button size="small" @click="copy(commands)">复制命令</el-button>
          </div>
          <p v-else class="muted">
            填写以 /
            开头的完整路径后显示命令；不要直接使用示例路径，也不需要套用 Windows
            的证书位置。
          </p>
          <p>
            在该 Linux
            电脑的终端执行以上命令，确认成功后完全重启游戏。使用独立证书库的客户端，还需在客户端的证书管理中单独导入。
          </p>
        </template>
        <p class="muted">
          下载入口只提供公开
          CA；不要使用同目录中的私钥文件。工作台无法自动确认游戏是否信任证书。
        </p>
      </section>
      <section>
        <h3>3. 配置游戏使用的 HTTP / HTTPS 代理</h3>
        <div class="guide-proxy-values">
          <span
            >服务器 <code>{{ host }}</code></span
          ><span
            >端口 <code>{{ port ?? "待读取" }}</code></span
          ><el-button
            v-if="port"
            text
            size="small"
            @click="copy(`${host}:${port}`)"
            >复制地址</el-button
          >
        </div>
        <template v-if="platform === 'windows'">
          <p v-if="hostPlatform === 'windows' && proxy?.supported">
            关闭本窗口后，可用顶部“接管系统代理”。已有 VPN
            或其他代理时，先核对弹窗中的原配置，再决定是否临时接管。
          </p>
          <p v-else>
            Windows
            工作台支持自动接管时可使用顶部按钮；按钮是否可用以实际运行环境为准。
          </p>
          <p>
            手动方式：设置 → 网络和 Internet → 代理 →
            手动设置代理，打开“使用代理服务器”，填写上方服务器和端口后保存。Windows
            10 / 11 中“设置 / 编辑”等名称可能不同。
          </p>
        </template>
        <ol v-else-if="platform === 'macos'">
          <li>
            打开“系统设置 → 网络”，选择实际使用的 Wi-Fi 或以太网 → “详细信息 →
            代理”。
          </li>
          <li>
            分别启用“网页代理（HTTP）”与“安全网页代理（HTTPS）”，两项均填写上方服务器和端口；本机采集代理未配置用户名、密码时，不要启用代理认证。
          </li>
          <li>
            点击“好 / 完成”保存。较旧 macOS 可从“系统偏好设置 → 网络 → 高级 →
            代理”进入同类设置。
          </li>
        </ol>
        <template v-else>
          <p>
            GNOME / Ubuntu：设置 → 网络 → 网络代理 → 手动，在 HTTP 和 HTTPS
            两项填写上方服务器和端口。
          </p>
          <p>
            KDE Plasma：系统设置 → 网络 → 代理，选择手动代理配置并填写 HTTP /
            HTTPS 两项。入口名称随桌面版本不同而变化。
          </p>
          <p>
            客户端不遵循桌面代理时，在客户端自己的网络设置中配置相同代理；不要仅凭桌面代理保存成功就认为游戏流量已经接入。
          </p>
        </template>
      </section>
      <section>
        <h3>4. 开始采集并核对结果</h3>
        <ol>
          <li>关闭本窗口，点击顶部“开始采集”；已经采集中时直接继续。</li>
          <li>
            完全退出并重新打开游戏，执行一次明确的游戏操作。只刷新工作台页面通常不会重建游戏连接。
          </li>
          <li>
            在“连接与诊断”核对收到的帧数和最后接收时间，再选择对应游戏连接。消息出现后，按实际协议名、ID
            或字段查找，不必使用固定示例协议。
          </li>
        </ol>
        <details>
          <summary>没有消息时，按现象检查</summary>
          <ul>
            <li>
              没有新帧：检查采集器、实际代理端口、游戏是否遵循代理，以及 VPN /
              TUN 是否改变了流量路径。
            </li>
            <li>
              游戏报证书错误：检查上述证书信任步骤；使用独立证书库的客户端需单独安装。部分客户端不接受代理证书。
            </li>
            <li>
              有帧但没有目标消息：核对“采集目标”的域名和 WebSocket
              路径，并重新连接游戏。
            </li>
            <li>
              消息未映射或部分解码：加载对应客户端版本的字典，再按提示开始新的采集。
            </li>
          </ul>
        </details>
      </section>
      <section>
        <h3>5. 保存记录并恢复网络设置</h3>
        <p>
          分析过程中可保存快照或开启持续落盘。取消“跟随”只暂停列表，停止采集才停止记录；关闭浏览器不会退出本机服务。
        </p>
        <p v-if="platform === 'windows'">
          通过工作台接管的代理，可用“恢复原代理”恢复；正常退出工作台时也会尝试恢复。手动修改的代理，请按原配置手动恢复。
        </p>
        <p v-else-if="platform === 'macos'">
          返回刚才的网络 → 代理设置，将 HTTP / HTTPS
          两项恢复为接入前的状态。原来使用其他代理时恢复原值；原来未启用时关闭这两项，再退出工作台并确认游戏可正常联网。
        </p>
        <p v-else>
          返回桌面或客户端的代理设置，恢复接入前的方式和数值；原来没有代理时改回“无代理”。退出工作台后确认游戏可正常联网。
        </p>
      </section>
    </div>
    <p class="muted guide-version-note">
      菜单路径依据系统文档整理，名称可能随系统版本、语言和桌面环境变化；此处不代表已在每种系统上实机验证。
    </p>
    <template #footer
      ><el-button type="primary" @click="emit('update:modelValue', false)"
        >返回工作台</el-button
      ></template
    >
  </el-dialog>
</template>

<style scoped>
.guide-status {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: var(--soft);
  border-radius: 4px;
}
.guide-status span {
  flex: 1;
  min-width: 180px;
}
.connection-steps {
  padding-right: 10px;
}
.connection-steps section + section {
  border-top: 1px solid var(--border);
  padding-top: 8px;
}
h3 {
  margin: 12px 0 6px;
  font-size: 14px;
}
p,
li {
  line-height: 1.8;
  font-size: 13px;
}
p {
  margin: 6px 0;
}
ol,
ul {
  padding-left: 23px;
  margin: 6px 0;
}
summary {
  cursor: pointer;
  font-size: 13px;
  margin: 6px 0;
}
.certificate-actions,
.guide-proxy-values {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  align-items: center;
}
.guide-download {
  color: var(--primary);
  text-decoration: underline;
}
.guide-command {
  padding: 10px;
  background: var(--background);
  overflow-wrap: anywhere;
}
.guide-path {
  margin: 8px 0;
}
.guide-version-note {
  margin-top: 10px;
}
.guide-platforms {
  margin: 10px 0 4px;
}
.connection-steps .el-input {
  margin-top: 8px;
}
</style>
<style>
.capture-connection-guide {
  display: flex;
  flex-direction: column;
  max-height: 92vh;
}
.capture-connection-guide .el-dialog__body {
  overflow-y: auto;
  min-height: 0;
}
.capture-connection-guide .el-dialog__header,
.capture-connection-guide .el-dialog__footer {
  flex-shrink: 0;
}
</style>
