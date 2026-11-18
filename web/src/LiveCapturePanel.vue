<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api, post } from "./types";
import { ElMessage, ElMessageBox } from "element-plus";
import SystemProxyControls from "./SystemProxyControls.vue";
import { changedCaptures, type CaptureProgress } from "./workspaceRefresh";

interface LiveConnection {
  connection: string;
  captureId: string;
  frames: number;
  messages: number;
  retainedMessages: number;
  clearVersion?: number;
  failed: number;
  endpoint: string;
  error: string;
}
interface LiveStatus {
  enabled: boolean;
  sessionRecordCount: number;
  sessionMessageCount: number;
  source: string;
  receivedFrames: number;
  duplicates: number;
  missingEvents: number;
  lastEventAt: string;
  lastError: string;
  connections: LiveConnection[];
  version: number;
  generation?: string;
}
const props = defineProps<{ activeCaptureId?: string; follow: boolean }>();
const emit = defineEmits<{
  changed: [connections: CaptureProgress[], changedIds: string[]];
  open: [captureId: string];
  "update:follow": [value: boolean];
  stopped: [];
  cleared: [ids: string[]];
}>();
const state = ref<LiveStatus>({
  enabled: false,
  sessionRecordCount: 0,
  sessionMessageCount: 0,
  source: "",
  receivedFrames: 0,
  duplicates: 0,
  missingEvents: 0,
  lastEventAt: "",
  lastError: "",
  connections: [],
  version: 0,
});
const busy = ref(false);
const stopDialog = ref(false);
const saveOnStop = ref(false);
const recordName = ref("");
const collector = ref({
  state: "NOT_STARTED",
  message: "",
  port: 0,
  certificateFile: "",
});
const proxy = ref<{
  supported: boolean;
  managed: boolean;
  existingIsTarget: boolean;
}>();
const proxyControls = ref<InstanceType<typeof SystemProxyControls>>();
const nextStep = computed(() => {
  if (!subscribed.value)
    return {
      title: "页面正在连接后端",
      hint: "连接恢复后会自动更新；长时间未恢复时请检查后端是否运行。",
    };
  if (collector.value.state === "PREPARING")
    return {
      title: "正在准备采集器",
      hint: "首次准备可能需要下载，完成后再开始采集。",
    };
  if (collector.value.state === "FAILED")
    return {
      title: "采集器未就绪",
      hint: collector.value.message || "展开技术状态查看原因，修复后刷新诊断。",
    };
  if (!state.value.enabled)
    return {
      title: "尚未开始采集",
      hint: "点击“开始采集”，然后进入游戏执行要分析的操作。",
    };
  if (
    proxy.value?.supported &&
    !proxy.value.managed &&
    !proxy.value.existingIsTarget
  )
    return {
      title: "采集已开始，等待流量接入",
      hint: "可点击“接管系统代理”；使用手动代理时请确认流量指向采集端口。",
    };
  if (!state.value.source)
    return {
      title: "等待游戏连接",
      hint: "重新进入游戏；已有游戏连接可能需要断开重连。",
    };
  return {
    title: "正在采集",
    hint: `已收到 ${state.value.receivedFrames} 帧；点击消息可暂停列表跟随，采集继续。`,
  };
});
async function refreshDiagnostics() {
  try {
    collector.value = await api("/api/collector");
    await proxyControls.value?.refresh();
  } catch (failure) {
    error.value = String(failure);
  }
}
async function confirmClearSession() {
  try {
    await ElMessageBox.confirm(
      "清空本次采集全部未保存记录的消息？记录和游戏连接会保留，已清空的消息无法恢复。",
      "清空本次采集",
      {
        type: "warning",
        confirmButtonText: "清空消息",
        cancelButtonText: "取消",
      },
    );
    await clearSession();
  } catch (failure) {
    if (failure !== "cancel" && failure !== "close")
      error.value = String(failure);
  }
}
function askStop() {
  saveOnStop.value = false;
  recordName.value = "";
  const stamp = props.activeCaptureId || state.value.connections[0]?.captureId;
  void api<{ name: string }[]>("/api/captures").then((items) => {
    recordName.value =
      (items as { name: string; id?: string }[]).find(
        (item) => item.id === stamp,
      )?.name || "";
  });
  stopDialog.value = true;
}
const subscribed = ref(false);
const error = ref("");
const instructions = ref(false);
const chosen = ref("");
let events: EventSource | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const pending = new Set<string>();
watch(
  () => props.activeCaptureId,
  (id) => {
    if (state.value.connections.some((item) => item.captureId === id))
      chosen.value = id || "";
  },
);
function update(status: LiveStatus) {
  if (status.version < state.value.version) return;
  const changes = changedCaptures(state.value.connections, status.connections);
  state.value = status;
  if (
    status.connections.some((item) => item.captureId === props.activeCaptureId)
  )
    chosen.value = props.activeCaptureId || "";
  else if (!status.connections.some((item) => item.captureId === chosen.value))
    chosen.value = status.connections[0]?.captureId || "";
  changes.forEach((id) => pending.add(id));
  // 心跳不查询列表；合并这一秒实际变化的连接，导航只由用户显式执行。
  if (pending.size && !timer) {
    timer = setTimeout(() => {
      timer = undefined;
      const changedIds = [...pending];
      pending.clear();
      emit("changed", state.value.connections, changedIds);
    }, 1000);
  }
}
async function control(action: "start" | "stop") {
  busy.value = true;
  error.value = "";
  try {
    update(
      await post<LiveStatus>(
        "/api/live/" + action,
        action === "stop"
          ? { save: saveOnStop.value, name: recordName.value }
          : {},
      ),
    );
    if (action === "stop") {
      stopDialog.value = false;
      emit("stopped");
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure);
  } finally {
    busy.value = false;
  }
}
const clearing = ref(false);
const canClearSession = computed(
  () => state.value.sessionMessageCount > 0 && !busy.value && !clearing.value,
);
async function clearSession() {
  if (!canClearSession.value) return;
  clearing.value = true;
  try {
    const result = await post<LiveStatus & { clearedCaptureIds: string[] }>(
      "/api/live/clear",
      {},
    );
    update(result);
    emit("cleared", result.clearedCaptureIds);
    ElMessage.success("本次会话消息已清空，记录与连接继续保留");
  } catch (failure) {
    error.value = String(failure);
  } finally {
    clearing.value = false;
  }
}
defineExpose({ clearSession, canClearSession, clearing });
function selectConnection() {
  if (chosen.value) emit("open", chosen.value);
}
onMounted(async () => {
  try {
    update(await api<LiveStatus>("/api/live/status"));
    collector.value = await api("/api/collector");
  } catch (failure) {
    error.value = String(failure);
  }
  events = new EventSource("/api/live/events");
  events.addEventListener("status", (event) => {
    try {
      update(JSON.parse((event as MessageEvent).data));
    } catch {
      error.value = "实时状态格式异常";
    }
  });
  events.onopen = () => {
    subscribed.value = true;
  };
  events.onerror = () => {
    subscribed.value = false;
  };
});
onBeforeUnmount(() => {
  events?.close();
  if (timer) clearTimeout(timer);
});
</script>
<template>
  <section class="live-toolbar">
    <div class="live-controls">
      <b>实时采集</b>
      <el-tag
        size="small"
        :type="state.enabled && !!state.source ? 'success' : 'info'"
        :title="nextStep.hint"
        >{{ nextStep.title }}</el-tag
      >
      <el-button
        v-if="!state.enabled"
        type="primary"
        size="small"
        :loading="busy"
        @click="control('start')"
        >开始采集</el-button
      >
      <el-button v-else size="small" :loading="busy" @click="askStop"
        >停止采集</el-button
      >
      <SystemProxyControls
        ref="proxyControls"
        compact
        @changed="proxy = $event"
      />
      <el-button text size="small" @click="instructions = true"
        >接入步骤</el-button
      >
      <el-checkbox
        :model-value="follow"
        size="small"
        @update:model-value="emit('update:follow', !!$event)"
        >跟随当前记录</el-checkbox
      >
      <el-dropdown trigger="click"
        ><el-button size="small">会话操作</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item
              :disabled="!canClearSession"
              @click="confirmClearSession"
              >清空本次采集消息</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
      <el-popover trigger="click" placement="bottom-start" :width="420">
        <template #reference
          ><el-button size="small" text>连接与诊断</el-button></template
        >
        <div class="live-diagnostics">
          <p class="next-step" role="status">{{ nextStep.hint }}</p>
          <span class="muted" :title="collector.message"
            >代理 :{{ collector.port }} ·
            {{
              collector.state === "RUNNING" ? "已随项目启动" : collector.message
            }}</span
          >
          <span class="muted"
            >{{ subscribed ? "页面推送正常" : "页面推送重连中" }} · 收到
            {{ state.receivedFrames }} 帧 · 去重 {{ state.duplicates }} · 缺号
            {{ state.missingEvents }}</span
          >
          <span class="muted">{{
            proxy?.managed
              ? "系统代理已接管"
              : proxy?.existingIsTarget
                ? "系统代理已指向采集器"
                : proxy?.supported
                  ? "系统代理未接管"
                  : "请使用手动代理接入"
          }}</span>
          <el-button text size="small" @click="refreshDiagnostics"
            >刷新诊断</el-button
          >
          <div v-if="state.connections.length" class="live-controls">
            <el-select
              v-model="chosen"
              placeholder="选择实时游戏连接"
              @change="selectConnection"
              ><el-option
                v-for="item in state.connections"
                :key="item.captureId"
                :value="item.captureId"
                :label="`${item.connection.slice(0, 8)} · ${item.retainedMessages ?? item.messages} 条 · ${item.endpoint}`"
            /></el-select>
            <el-button text size="small" @click="selectConnection"
              >查看此连接</el-button
            >
            <span class="muted"
              >开启后重新进入小游戏；中途接入缺少压缩上下文时需重连游戏。</span
            >
          </div>
        </div>
      </el-popover>
    </div>
    <div v-if="state.lastError || error" class="error-bar" role="alert">
      {{ error || state.lastError }}
    </div>
    <el-dialog
      v-model="stopDialog"
      title="停止采集"
      width="540px"
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
    >
      <p>停止后不再记录新消息，采集代理继续运行，游戏仍可联网。</p>
      <el-checkbox v-model="saveOnStop">保存本轮连接记录到本机</el-checkbox>
      <el-input
        v-if="saveOnStop"
        v-model="recordName"
        maxlength="100"
        placeholder="默认使用每条连接开始采集的时间"
      />
      <p class="muted">
        不保存时仅保留临时缓存，退出后端会丢失，也可稍后选中记录保存。已淘汰的消息无法恢复。
      </p>
      <template #footer
        ><el-button :disabled="busy" @click="stopDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="control('stop')">{{
          saveOnStop ? "停止并保存" : "仅停止，不保存"
        }}</el-button></template
      >
    </el-dialog>
    <el-dialog v-model="instructions" title="实时采集使用步骤" width="660px">
      <ol class="guide">
        <li>
          启动项目会自动启动采集器。确认代理已就绪后点击“开始采集”，无需单独运行
          collect-protolens.cmd。
        </li>
        <li>
          首次使用安装
          {{
            collector.certificateFile ||
            "数据目录中的 mitmproxy-conf/mitmproxy-ca-cert.cer"
          }}
          到当前用户的“受信任的根证书颁发机构”。只有这份公开证书需要安装，不能分享同目录内的
          CA 私钥文件。
        </li>
        <li>
          工作台会尝试自动接管系统代理；检测到已有代理时先确认，或点击“保留原代理”。
          也可用“接管系统代理 / 恢复原代理”手动切换，代理目标为 127.0.0.1:{{
            collector.port
          }}。确认后重开小游戏。
        </li>
        <li>
          在上方选择游戏连接，搜索 CgAllianceTechDonate
          或其他协议。消息约每秒刷新，点击查看字段、JSON 与原始业务包。
        </li>
        <li>
          取消跟随只暂停页面；消息继续进入有界缓存。停止时自行选择是否保存。
          已由工作台接管的系统代理会在退出时恢复；外部软件改过设置时保留它的新配置。
        </li>
      </ol>
      <p class="muted">
        独立采集器使用 mitmproxy；Fiddler 的 SAZ
        离线导入仍保留。采集器提供已经解压、重组的完整业务消息，因此实时记录有业务包
        HEX，不声称保留原始 WebSocket wire
        帧。后端或采集器重启后需重新开始并重进小游戏。
      </p>
    </el-dialog>
  </section>
</template>
<style scoped>
.live-toolbar {
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  padding: 3px 12px;
  flex-shrink: 0;
}
.live-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-height: 28px;
}
.live-controls + .live-controls {
  margin-top: 6px;
}
.live-controls .el-select {
  width: min(640px, 100%);
}
.script-link {
  font-size: 12px;
  color: var(--primary);
  text-decoration: none;
}
.script-link:hover {
  text-decoration: underline;
}
.live-controls .el-checkbox {
  margin-right: 0;
}
.next-step {
  margin: 4px 0;
  font-size: 12px;
  color: var(--text);
}
.live-diagnostics {
  font-size: 12px;
}
.live-diagnostics summary {
  cursor: pointer;
  color: var(--muted);
}
.live-diagnostics > span {
  display: inline-block;
  margin-right: 12px;
}
.live-toolbar > .error-bar {
  margin-top: 6px;
}
</style>
