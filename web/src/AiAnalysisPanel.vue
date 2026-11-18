<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { api, type Capture } from "./types";

const props = defineProps<{ capture?: Capture; selectedIndex?: number }>();
const visible = ref(false);
const busy = ref(false);
const error = ref("");
const active = ref<"analyze" | "connect" | "tools">("analyze");
const question = ref("");
const connection = ref<{
  url: string;
  config: unknown;
  tools: { name: string; description: string }[];
}>();
const defaultQuestion =
  "请梳理这次操作的请求、响应、主动推送，以及客户端数据更新和 UI 刷新流程，附消息编号与源码行号，区分事实与推断。";
const presets = [
  { label: "还原交互流程", value: defaultQuestion },
  {
    label: "追踪字段刷新",
    value:
      "请找出这次操作中变化的关键字段，沿客户端代码追踪数据更新及 UI 刷新入口，附消息编号与源码行号。",
  },
  {
    label: "核对可见范围",
    value:
      "请结合抓包与客户端代码，分析这次操作对不同身份和联盟的可见条件。区分已证实的行为与需要其他账号验证的广播范围。",
  },
];
const toolGroups = [
  {
    title: "抓包与消息",
    description: "从操作区间定位请求与推送",
    icon: "↔",
    names: [
      "list_captures",
      "get_capture_overview",
      "search_messages",
      "get_message",
      "get_message_context",
    ],
  },
  {
    title: "协议字段",
    description: "核对名称、类型与字典版本",
    icon: "{ }",
    names: ["find_protocols", "get_protocol_definition"],
  },
  {
    title: "客户端源码",
    description: "追踪接收处理与页面刷新",
    icon: "⌘",
    names: ["search_client_source", "read_client_source"],
  },
];
const toolLabels: Record<string, string> = {
  list_captures: "选择抓包记录",
  get_capture_overview: "查看记录与协议分布",
  search_messages: "搜索消息",
  get_message: "读取字段详情",
  get_message_context: "查看前后消息",
  find_protocols: "查找协议",
  get_protocol_definition: "读取字段定义",
  search_client_source: "搜索客户端代码",
  read_client_source: "读取源码片段",
};
const configText = computed(() =>
  connection.value ? JSON.stringify(connection.value.config, null, 2) : "",
);
const range = computed(() =>
  props.capture
    ? `#${props.capture.firstIndex ?? 0} — #${props.capture.lastIndex ?? Math.max(0, props.capture.messageCount - 1)}`
    : "",
);
const promptText = computed(() => {
  const item = props.capture;
  return `${question.value.trim() || defaultQuestion}\n\n使用 protolens MCP 工具查询：\n${item ? `记录：${item.name}\ncaptureId：${item.id}\n原始消息编号范围：${item.firstIndex ?? 0}～${item.lastIndex ?? Math.max(0, item.messageCount - 1)}\n${props.selectedIndex !== undefined ? `关注消息 #${props.selectedIndex}\n` : ""}已淘汰：${item.evictedCount || 0} 条\n` : "先列出抓包记录，询问我选择哪一份，不要自行认定最新连接。\n"}先查看摘要和操作区间，再检索相关协议及消息上下文，结合客户端源码核对。不要把单账号收到的消息推断为所有成员广播。`;
});
async function loadConnection() {
  busy.value = true;
  error.value = "";
  try {
    connection.value = await api("/api/mcp/connection");
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure);
  } finally {
    busy.value = false;
  }
}
function open() {
  active.value = "analyze";
  visible.value = true;
  void loadConnection();
}
async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制，可以粘贴到 AI 客户端");
  } catch {
    ElMessage.warning("自动复制失败，请展开文本后手动复制");
  }
}
function toolDescription(name: string) {
  return (
    connection.value?.tools.find((tool) => tool.name === name)?.description ||
    ""
  );
}
</script>

<template>
  <el-button text @click="open">AI 分析 / MCP</el-button>
  <el-dialog
    v-model="visible"
    class="ai-analysis-dialog"
    width="min(720px, calc(100vw - 32px))"
    top="8vh"
    :show-close="false"
    aria-label="AI 分析"
  >
    <template #header>
      <div class="ai-heading">
        <span class="ai-heading-icon" aria-hidden="true"
          ><svg viewBox="0 0 24 24" fill="none">
            <path
              d="m12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4L12 3Z"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linejoin="round"
            /></svg
        ></span>
        <div>
          <h2>AI 协议分析</h2>
          <p>让 AI 结合抓包和客户端代码，还原业务交互</p>
        </div>
        <button
          class="ai-close"
          aria-label="关闭 AI 分析"
          @click="visible = false"
        >
          <svg viewBox="0 0 24 24" fill="none">
            <path
              d="m6 6 12 12M18 6 6 18"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
            />
          </svg>
        </button>
      </div>
      <nav class="ai-navigation" aria-label="AI 分析功能">
        <button
          v-for="tab in [
            { key: 'analyze', label: '分析记录' },
            { key: 'connect', label: '连接 AI' },
            { key: 'tools', label: '查询能力' },
          ]"
          :key="tab.key"
          :class="{ selected: active === tab.key }"
          :aria-pressed="active === tab.key"
          @click="active = tab.key as typeof active"
        >
          {{ tab.label }}
        </button>
      </nav>
    </template>

    <div class="ai-body">
      <div v-if="error" class="ai-inline-error" role="alert">
        <span>{{ error }}</span
        ><el-button text size="small" @click="loadConnection">重试</el-button>
      </div>
      <section v-if="active === 'analyze'" class="ai-view">
        <div v-if="capture" class="ai-record">
          <div class="ai-record-title">
            <span class="ai-caption">当前分析对象</span
            ><span
              class="ai-badge"
              :class="{ snapshot: capture.analysisSnapshot }"
              >{{
                capture.analysisSnapshot
                  ? "分析快照"
                  : capture.temporary
                    ? "实时缓存"
                    : "已保存记录"
              }}</span
            >
          </div>
          <strong :title="capture.name">{{ capture.name }}</strong>
          <div class="ai-record-meta">
            <span>{{ capture.messageCount.toLocaleString() }} 条消息</span
            ><span>{{ range }}</span
            ><span v-if="selectedIndex !== undefined"
              >关注 #{{ selectedIndex }}</span
            >
          </div>
          <details class="ai-record-id">
            <summary>记录标识</summary>
            <code>{{ capture.id }}</code>
          </details>
        </div>
        <div v-else class="ai-empty">
          <b>先选择一份抓包记录</b>
          <p>从左侧记录列表选中目标，提问会自动带上对应的消息范围。</p>
        </div>

        <div class="ai-question-heading">
          <label for="ai-question">想了解什么？</label
          ><span>描述操作、身份和预期表现</span>
        </div>
        <el-input
          id="ai-question"
          v-model="question"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 7 }"
          resize="none"
          maxlength="8000"
          placeholder="例如：我作为攻城方建造了霹雳车，想知道推荐到达时间如何下发，以及不同联盟的按钮为什么会显示。"
        />
        <div class="ai-presets">
          <span>快速填入</span
          ><button
            v-for="preset in presets"
            :key="preset.label"
            @click="question = preset.value"
          >
            {{ preset.label }}<span aria-hidden="true"> ↗</span>
          </button>
        </div>
        <div class="ai-helper">
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path
              d="M6 3h8v14H6zM3 6h3m8 0h3M3 10h3m8 0h3M3 14h3m8 0h3"
              stroke="currentColor"
              stroke-width="1.3"
              stroke-linejoin="round"
            /></svg
          ><span
            >复制时自动附上记录 ID、消息范围和证据要求，无需手动整理。</span
          >
        </div>
        <p v-if="capture?.evictedCount" class="ai-retention">
          已有 {{ capture.evictedCount.toLocaleString() }} 条消息被淘汰，AI
          只能分析仍保留的部分。
        </p>
        <details class="ai-disclosure">
          <summary>预览发送给 AI 的完整提问</summary>
          <pre tabindex="0">{{ promptText }}</pre>
        </details>
      </section>

      <section v-else-if="active === 'connect'" class="ai-view">
        <div class="ai-section-intro">
          <h3>首次连接，只需配置一次</h3>
          <p>在你的 AI 客户端添加 MCP 服务，模型继续由客户端管理。</p>
        </div>
        <div class="ai-endpoint">
          <span class="ai-caption">服务地址</span>
          <div>
            <code>{{
              connection?.url || (busy ? "正在读取…" : "暂不可用")
            }}</code
            ><el-button
              text
              size="small"
              :disabled="!connection"
              @click="copy(connection!.url)"
              >复制地址</el-button
            >
          </div>
          <span class="ai-endpoint-kind"
            >Streamable HTTP <span>·</span> 本机只读</span
          >
        </div>
        <ol class="ai-connect-steps">
          <li>
            <span>1</span>
            <div>
              <b>添加 MCP 服务</b>
              <p>在 AI 客户端的 MCP 设置中，选择 Streamable HTTP。</p>
            </div>
          </li>
          <li>
            <span>2</span>
            <div>
              <b>粘贴连接配置</b>
              <p>
                复制下方配置导入；手动添加时填写服务地址和 Authorization
                请求头。
              </p>
            </div>
          </li>
          <li>
            <span>3</span>
            <div>
              <b>回到这里，复制分析提问</b>
              <p>连接启用后，AI 即可按需查询消息、字段和源码。</p>
            </div>
          </li>
        </ol>
        <details class="ai-disclosure">
          <summary>查看完整配置（包含本机凭证）</summary>
          <pre tabindex="0">{{ configText || "连接配置读取后显示在这里" }}</pre>
        </details>
      </section>

      <section v-else class="ai-view">
        <div class="ai-section-intro">
          <h3>从消息到源码，逐步核对证据</h3>
          <p>AI 按需查询，不必把整份抓包一次塞进对话。</p>
        </div>
        <div class="ai-capabilities">
          <details
            v-for="group in toolGroups"
            :key="group.title"
            class="ai-capability"
          >
            <summary>
              <span class="ai-capability-icon" aria-hidden="true">{{
                group.icon
              }}</span
              ><span
                ><b>{{ group.title }}</b
                ><small>{{ group.description }}</small></span
              ><span class="ai-tool-count">{{ group.names.length }} 项</span
              ><svg
                class="ai-chevron"
                viewBox="0 0 20 20"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="m6 8 4 4 4-4"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </summary>
            <div class="ai-tool-list">
              <div v-for="name in group.names" :key="name">
                <b>{{ toolLabels[name] }}</b
                ><code>{{ name }}</code>
                <p>{{ toolDescription(name) }}</p>
              </div>
            </div>
          </details>
        </div>
        <p class="ai-tools-note">
          每个结论可回查消息编号与源码行号。广播范围和因果关系仍需证据验证。
        </p>
      </section>
    </div>

    <template #footer>
      <div class="ai-footer">
        <span class="ai-footer-note"
          ><span class="ai-safe-dot"></span
          >{{
            active === "analyze"
              ? "粘贴到已连接 MCP 的 AI 对话"
              : "只读查询 · 不执行游戏操作"
          }}</span
        >
        <div>
          <el-button v-if="active === 'analyze'" @click="active = 'connect'"
            >连接设置</el-button
          ><el-button
            v-if="active === 'analyze'"
            type="primary"
            @click="copy(promptText)"
            >复制分析提问 <span aria-hidden="true">↗</span></el-button
          ><el-button
            v-else-if="active === 'connect'"
            type="primary"
            :loading="busy"
            :disabled="!connection"
            @click="copy(configText)"
            >复制 MCP 配置</el-button
          ><el-button v-else type="primary" @click="active = 'analyze'"
            >开始分析</el-button
          >
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style>
.ai-analysis-dialog.el-dialog {
  padding: 0;
  border-radius: 12px;
  overflow: hidden;
  max-height: 84vh;
  display: flex;
  flex-direction: column;
  color: var(--text);
  background: var(--surface);
  box-shadow: 0 24px 80px #0003;
}
.ai-analysis-dialog .el-dialog__header {
  margin: 0;
  padding: 24px 28px 0;
}
.ai-analysis-dialog .el-dialog__body {
  padding: 0;
  overflow-y: auto;
  min-height: 0;
  color: var(--text);
}
.ai-analysis-dialog .el-dialog__footer {
  padding: 16px 28px;
  border-top: 1px solid var(--border);
  background: var(--surface);
}
.ai-analysis-dialog .ai-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-analysis-dialog .ai-heading-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  background: var(--soft);
  color: var(--primary);
  border-radius: 11px;
}
.ai-analysis-dialog .ai-heading-icon svg {
  width: 26px;
  height: 26px;
}
.ai-analysis-dialog h2 {
  margin: 0;
  font-size: 19px;
  line-height: 1.5;
  font-weight: 650;
  letter-spacing: 0.2px;
}
.ai-analysis-dialog .ai-heading p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
.ai-analysis-dialog .ai-close {
  align-self: flex-start;
  margin-left: auto;
  width: 28px;
  height: 28px;
  padding: 5px;
  background: transparent;
  color: var(--muted);
  border: 0;
  border-radius: 6px;
}
.ai-analysis-dialog .ai-close:hover {
  background: var(--background);
  color: var(--text);
}
.ai-analysis-dialog .ai-close svg {
  width: 18px;
  height: 18px;
}
.ai-analysis-dialog .ai-navigation {
  display: flex;
  gap: 4px;
  margin-top: 22px;
  border-bottom: 1px solid var(--border);
}
.ai-analysis-dialog .ai-navigation button {
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--muted);
  background: transparent;
  padding: 11px 16px 12px;
  font-size: 13px;
  font-weight: 500;
}
.ai-analysis-dialog .ai-navigation button:hover {
  color: var(--primary);
  background: var(--soft);
}
.ai-analysis-dialog .ai-navigation button.selected {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 650;
}
.ai-analysis-dialog .ai-body {
  padding: 22px 28px 24px;
}
.ai-analysis-dialog .ai-record {
  border: 1px solid var(--border);
  background: var(--background);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 22px;
}
.ai-analysis-dialog .ai-record-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
  gap: 10px;
}
.ai-analysis-dialog .ai-caption {
  font-size: 11px;
  color: var(--muted);
}
.ai-analysis-dialog .ai-badge {
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: 10px;
  border-radius: 4px;
  padding: 2px 6px;
}
.ai-analysis-dialog .ai-badge.snapshot {
  color: var(--primary);
  background: var(--soft);
  border-color: transparent;
}
.ai-analysis-dialog .ai-record > strong {
  display: block;
  font-size: 14px;
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-analysis-dialog .ai-record-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  font-size: 11px;
  color: var(--muted);
  margin-top: 7px;
}
.ai-analysis-dialog .ai-record-id {
  margin-top: 9px;
  font-size: 11px;
  color: var(--muted);
}
.ai-analysis-dialog .ai-record-id summary {
  cursor: pointer;
}
.ai-analysis-dialog .ai-record-id code {
  display: block;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
.ai-analysis-dialog .ai-question-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.ai-analysis-dialog .ai-question-heading label {
  font-size: 14px;
  font-weight: 600;
}
.ai-analysis-dialog .ai-question-heading > span {
  font-size: 11px;
  color: var(--muted);
}
.ai-analysis-dialog .el-textarea__inner {
  background: var(--surface);
  color: var(--text);
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.8;
  box-shadow: 0 0 0 1px var(--border) inset;
}
.ai-analysis-dialog .el-textarea__inner:focus {
  box-shadow: 0 0 0 1px var(--primary) inset;
}
.ai-analysis-dialog .el-textarea__inner::placeholder {
  color: var(--muted);
  opacity: 0.7;
}
.ai-analysis-dialog .ai-presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin: 11px 0 16px;
}
.ai-analysis-dialog .ai-presets > span {
  font-size: 11px;
  color: var(--muted);
  margin-right: 2px;
}
.ai-analysis-dialog .ai-presets button {
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--muted);
  border-radius: 5px;
  padding: 4px 8px;
  font-size: 11px;
}
.ai-analysis-dialog .ai-presets button:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--soft);
}
.ai-analysis-dialog .ai-helper {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.7;
}
.ai-analysis-dialog .ai-helper svg {
  width: 16px;
  height: 16px;
  color: var(--primary);
  flex-shrink: 0;
}
.ai-analysis-dialog .ai-disclosure {
  margin-top: 18px;
  color: var(--muted);
  font-size: 12px;
}
.ai-analysis-dialog .ai-disclosure summary {
  cursor: pointer;
  line-height: 1.6;
}
.ai-analysis-dialog .ai-disclosure summary:hover {
  color: var(--primary);
}
.ai-analysis-dialog .ai-disclosure pre {
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  background: var(--background);
  color: var(--text);
  max-height: 200px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.8;
}
.ai-analysis-dialog .ai-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.ai-analysis-dialog .ai-footer > div {
  display: flex;
  gap: 8px;
}
.ai-analysis-dialog .ai-footer .el-button {
  margin-left: 0;
  border-radius: 6px;
  font-size: 12px;
}
.ai-analysis-dialog .ai-footer-note {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 11px;
  text-align: left;
}
.ai-analysis-dialog .ai-safe-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--success);
  flex-shrink: 0;
}
.ai-analysis-dialog .ai-section-intro h3 {
  font-size: 14px;
  margin: 0 0 7px;
  font-weight: 600;
}
.ai-analysis-dialog .ai-section-intro p {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 18px;
}
.ai-analysis-dialog .ai-endpoint {
  padding: 13px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--background);
}
.ai-analysis-dialog .ai-endpoint > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 3px;
}
.ai-analysis-dialog .ai-endpoint code {
  font-family: Consolas, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.ai-analysis-dialog .ai-endpoint-kind {
  display: block;
  font-size: 10px;
  color: var(--muted);
  margin-top: 2px;
}
.ai-analysis-dialog .ai-endpoint-kind > span {
  padding: 0 5px;
}
.ai-analysis-dialog .ai-connect-steps {
  margin: 20px 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 17px;
}
.ai-analysis-dialog .ai-connect-steps li {
  display: flex;
  gap: 11px;
}
.ai-analysis-dialog .ai-connect-steps li > span {
  display: grid;
  place-items: center;
  background: var(--soft);
  color: var(--primary);
  width: 22px;
  height: 22px;
  border-radius: 50%;
  font-size: 11px;
  flex-shrink: 0;
}
.ai-analysis-dialog .ai-connect-steps b {
  font-size: 12px;
  font-weight: 600;
}
.ai-analysis-dialog .ai-connect-steps p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}
.ai-analysis-dialog .ai-capabilities {
  display: grid;
  gap: 10px;
}
.ai-analysis-dialog .ai-capability {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.ai-analysis-dialog .ai-capability > summary {
  display: flex;
  align-items: center;
  list-style: none;
  gap: 12px;
  padding: 15px;
  cursor: pointer;
}
.ai-analysis-dialog .ai-capability > summary::-webkit-details-marker {
  display: none;
}
.ai-analysis-dialog .ai-capability > summary:hover {
  background: var(--background);
}
.ai-analysis-dialog .ai-capability-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: var(--soft);
  color: var(--primary);
  font-size: 17px;
  font-family: Consolas, monospace;
}
.ai-analysis-dialog .ai-capability b {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.ai-analysis-dialog .ai-capability small {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-top: 4px;
}
.ai-analysis-dialog .ai-tool-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--muted);
  white-space: nowrap;
}
.ai-analysis-dialog .ai-chevron {
  display: block;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: var(--muted);
  transition:
    transform 160ms ease,
    color 160ms ease;
}
.ai-analysis-dialog .ai-capability > summary:hover .ai-chevron {
  color: var(--primary);
}
.ai-analysis-dialog .ai-capability[open] .ai-chevron {
  transform: rotate(180deg);
}
.ai-analysis-dialog .ai-tool-list {
  border-top: 1px solid var(--border);
  padding: 0 16px;
  background: var(--background);
}
.ai-analysis-dialog .ai-tool-list > div {
  padding: 12px 0;
}
.ai-analysis-dialog .ai-tool-list > div + div {
  border-top: 1px solid var(--border);
}
.ai-analysis-dialog .ai-tool-list b {
  font-size: 12px;
}
.ai-analysis-dialog .ai-tool-list code {
  color: var(--primary);
  display: block;
  font-size: 10px;
  margin-top: 4px;
}
.ai-analysis-dialog .ai-tool-list p {
  font-size: 11px;
  line-height: 1.7;
  margin: 6px 0 0;
  color: var(--muted);
}
.ai-analysis-dialog .ai-tools-note {
  font-size: 11px;
  line-height: 1.8;
  color: var(--muted);
  margin: 16px 0 0;
}
.ai-analysis-dialog .ai-empty {
  background: var(--background);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 22px;
}
.ai-analysis-dialog .ai-empty b {
  font-size: 13px;
}
.ai-analysis-dialog .ai-empty p {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.7;
  margin: 6px 0 0;
}
.ai-analysis-dialog .ai-inline-error {
  padding: 10px 12px;
  background: var(--background);
  color: var(--danger);
  display: flex;
  gap: 12px;
  justify-content: space-between;
  font-size: 12px;
  border-radius: 6px;
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}
.ai-analysis-dialog .ai-retention {
  color: var(--warning);
  font-size: 11px;
  margin: 10px 0 0;
}
@media (max-width: 600px) {
  .ai-analysis-dialog.el-dialog {
    margin-top: 24px;
    max-height: calc(100dvh - 48px);
  }
  .ai-analysis-dialog .el-dialog__header {
    padding: 20px 18px 0;
  }
  .ai-analysis-dialog .ai-body {
    padding: 18px;
  }
  .ai-analysis-dialog .el-dialog__footer {
    padding: 14px 18px;
  }
  .ai-analysis-dialog .ai-footer {
    flex-wrap: wrap;
  }
  .ai-analysis-dialog .ai-footer > div {
    margin-left: auto;
  }
  .ai-analysis-dialog .ai-question-heading > span {
    display: none;
  }
  .ai-analysis-dialog .ai-heading p {
    max-width: 240px;
  }
}
</style>
