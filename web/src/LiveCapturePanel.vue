<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api, post } from "./types";
import { ElMessage, ElMessageBox } from "element-plus";
import SystemProxyControls from "./SystemProxyControls.vue";
import CaptureConnectionGuide from "./CaptureConnectionGuide.vue";
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
  if (state.value.source && state.value.receivedFrames > 0)
    return {
      title: "正在采集",
      hint: `已收到 ${state.value.receivedFrames} 帧；点击消息只暂停列表跟随，后台采集继续。`,
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
    title: "采集源已接入，等待消息",
    hint: "在游戏执行目标操作，确认消息数增加；可在连接与诊断查看最后接收时间。",
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
let disposed = false;
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
  if (disposed) return;
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
  if (pending.size && !timer && document.visibilityState === "visible") {
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
function syncEventSubscription() {
  // 后台采集由服务端负责；隐藏页面不占用长连接或反复更新列表。
  // 切回页面重新订阅并补读状态，累计消息计数仍由服务端提供。
  if (disposed || document.visibilityState !== "visible") {
    events?.close();
    events = undefined;
    subscribed.value = false;
    if (timer) clearTimeout(timer);
    timer = undefined;
    return;
  }
  if (events) return;
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
}
async function visibilityChanged() {
  syncEventSubscription();
  if (document.visibilityState !== "visible") return;
  try {
    update(await api<LiveStatus>("/api/live/status"));
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure);
  }
}
onMounted(async () => {
  document.addEventListener("visibilitychange", visibilityChanged);
  try {
    update(await api<LiveStatus>("/api/live/status"));
    collector.value = await api("/api/collector");
  } catch (failure) {
    error.value = String(failure);
  }
  syncEventSubscription();
});
onBeforeUnmount(() => {
  disposed = true;
  document.removeEventListener("visibilitychange", visibilityChanged);
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
      <el-button
        size="small"
        :disabled="!canClearSession"
        :loading="clearing"
        title="清空本次采集的未保存消息，保留记录和游戏连接"
        @click="confirmClearSession"
        >清空本次采集消息</el-button
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
          <span class="muted"
            >最后接收事件：{{
              state.lastEventAt
                ? new Date(state.lastEventAt).toLocaleString()
                : "尚未收到"
            }}</span
          >
          <span class="muted"
            >证书是否已被游戏信任需按接入步骤验证，工作台无法自动确认。</span
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
    <CaptureConnectionGuide
      v-model="instructions"
      :live="state"
      :proxy="proxy"
      @refresh="refreshDiagnostics"
    />
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
