<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import JsonTree from "./JsonTree.vue";
import SplitPane from "./SplitPane.vue";
import LiveCapturePanel from "./LiveCapturePanel.vue";
import AiAnalysisPanel from "./AiAnalysisPanel.vue";
import CollectorSettingsPanel from "./CollectorSettingsPanel.vue";
import { api, post, type Capture, type Packet, type Page } from "./types";
import {
  reconcileRows,
  RequestGate,
  shouldRefreshMessages,
  shouldCountUnread,
  type CaptureProgress,
} from "./workspaceRefresh";

const livePanel = ref<InstanceType<typeof LiveCapturePanel>>();
const captures = shallowRef<Capture[]>([]);
const capture = shallowRef<Capture>();
const selected = shallowRef<Packet>();
const rows = shallowRef<Packet[]>([]);
const total = ref(0);
const page = ref(1);
const busy = ref(false);
const loading = ref(false);
const interactiveLoading = ref(false);
const showAdvancedColumns = ref(false);
const health = ref({
  ready: false,
  catalog: {
    messageCount: 0,
    definitionCount: 0,
    sha256: "",
    sourceFile: "",
    sourceRoot: "",
  },
});
const search = ref("");
const protocolId = ref("");
const order = ref(localStorage.getItem("analyzer.order") || "desc");
const include = ref("");
const exclude = ref("");
const appliedInclude = ref("");
const appliedExclude = ref("");
const filterDialog = ref(false);
const settingsDialog = ref(false);
const settingsTab = ref("protocol");
const settingsBusy = ref(false);
const update = ref({
  currentVersion: "",
  latestVersion: "",
  downloadUrl: "",
  fileName: "",
  available: false,
  releaseUrl: "",
});
const updateBusy = ref(false);
const settings = ref({
  serverPort: 0,
  frontendPort: 0,
  backendAddress: "",
  browserAddress: "",
  proxyAddress: "",
  configFile: "",
  configPrecedence: "",
  competitorPath: "",
  protocolFile: "",
  loadedProtocolFile: "",
  maxMessages: 10000,
  maxCacheMiB: 64,
  collectorPort: 18888,
  autoStartCollector: true,
  catalogError: "",
});
interface ProtocolCandidate {
  path: string;
  relativePath: string;
  sizeBytes: number;
  modifiedAt: string;
}
const protocolCandidates = ref<ProtocolCandidate[]>([]);
const selectedProtocolFile = ref("");
function sourceChanged() {
  protocolCandidates.value = [];
  selectedProtocolFile.value = "";
}
async function scanProtocolFiles() {
  const result = await post<{ candidates: ProtocolCandidate[] }>(
    "/api/catalog/discover",
    { path: settings.value.competitorPath },
  );
  protocolCandidates.value = result.candidates;
  const chosen = result.candidates.find(
    (item) =>
      item.path === selectedProtocolFile.value ||
      item.relativePath === selectedProtocolFile.value,
  );
  selectedProtocolFile.value =
    chosen?.path ||
    (result.candidates.length === 1 ? result.candidates[0].path : "");
  if (!result.candidates.length)
    ElMessage.warning("没有找到 thrift_data.js，请检查路径或直接填写文件路径");
}
async function findProtocolFiles() {
  settingsBusy.value = true;
  try {
    await scanProtocolFiles();
  } catch (error) {
    failure(error);
  } finally {
    settingsBusy.value = false;
  }
}
async function openSettings() {
  try {
    settings.value = await api("/api/settings");
    protocolCandidates.value = [];
    selectedProtocolFile.value = settings.value.protocolFile || "";
    settingsDialog.value = true;
  } catch (error) {
    failure(error);
  }
}
async function checkUpdate() {
  try {
    update.value = await api("/api/update");
    if (update.value.available)
      ElMessage.success(`发现 ProtoLens ${update.value.latestVersion}`);
    else ElMessage.info("当前已是最新版本");
  } catch (error) {
    failure(error);
  }
}
async function downloadUpdate() {
  if (!update.value.downloadUrl) return;
  updateBusy.value = true;
  try {
    const response = await fetch(update.value.downloadUrl);
    if (!response.ok) throw new Error(`下载失败：HTTP ${response.status}`);
    const blob = await response.blob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = update.value.fileName || "ProtoLens-update";
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    ElMessage.success("安装包已开始下载");
  } catch (error) {
    failure(error);
  } finally {
    updateBusy.value = false;
  }
}
async function saveSettings() {
  settingsBusy.value = true;
  try {
    settings.value = await post("/api/settings", settings.value);
    ElMessage.success("缓存设置已保存，新连接生效");
  } catch (error) {
    failure(error);
  } finally {
    settingsBusy.value = false;
  }
}
async function reloadCatalog() {
  settingsBusy.value = true;
  try {
    await scanProtocolFiles();
    if (!selectedProtocolFile.value) {
      if (protocolCandidates.value.length > 1)
        ElMessage.warning("找到多个协议文件，请从下拉列表选择要加载的一份");
      return;
    }
    health.value.catalog = await post("/api/catalog/reload", {
      path: settings.value.competitorPath,
      file: selectedProtocolFile.value,
    });
    settings.value = await api("/api/settings");
    ElMessage.success("已加载协议，新连接使用新字典；已有记录保持原字典");
  } catch (error) {
    failure(error);
  } finally {
    settingsBusy.value = false;
  }
}
async function deleteCapture(item: Capture) {
  try {
    await ElMessageBox.confirm(
      `删除“${item.name}”？此操作会清除该记录及本机保存文件。`,
      "删除抓包记录",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
    await api(`/api/captures/${item.id}`, { method: "DELETE" });
    if (capture.value?.id === item.id) {
      messageGate.cancel();
      detailGate.cancel();
      pendingLive = false;
      capture.value = undefined;
      selected.value = undefined;
      selectedRowIndex.value = undefined;
      rows.value = [];
      total.value = 0;
    }
    for (const key of detailCache.keys())
      if (key.startsWith(item.id + ":")) detailCache.delete(key);
    await reloadCaptures();
  } catch (error) {
    if (error !== "cancel" && error !== "close") failure(error);
  }
}
async function sessionCleared(ids: string[]) {
  const cleared = new Set(ids);
  const currentId = capture.value?.id;
  const nextUnread = { ...unread.value };
  ids.forEach((id) => {
    nextUnread[id] = 0;
  });
  unread.value = nextUnread;
  if (currentId && cleared.has(currentId)) {
    messageGate.cancel();
    detailGate.cancel();
    if (refreshTimer) clearTimeout(refreshTimer);
    pendingLive = false;
    selected.value = undefined;
    selectedRowIndex.value = undefined;
    rows.value = [];
    total.value = 0;
    selectedRange.value = "";
    browsingHistory.value = false;
    page.value = 1;
    unread.value = { ...unread.value, [currentId]: 0 };
  }
  if (operationStart.value && cleared.has(operationStart.value.captureId))
    operationStart.value = undefined;
  for (const key of detailCache.keys()) {
    if (ids.some((id) => key.startsWith(id + ":"))) detailCache.delete(key);
  }
  await reloadCaptures();
  // 清空期间用户若切换记录，不能把其导航抢回；同一记录则保留 ID、筛选与跟随选择。
  if (currentId && cleared.has(currentId) && capture.value?.id === currentId) {
    const summary = captures.value.find((item) => item.id === currentId);
    if (summary) capture.value = summary;
    await loadMessages(
      autoFollow.value && capture.value?.liveState === "CAPTURING",
      true,
    );
  }
}
async function saveCapture() {
  if (!capture.value) return;
  try {
    const result = await ElMessageBox.prompt(
      "记录名称（默认采集开始时间）",
      "保存记录",
      {
        inputValue: capture.value.name,
        inputValidator: (value) =>
          (!!value?.trim() && value.length <= 100) || "请输入 1～100 字名称",
      },
    );
    capture.value = await post<Capture>(
      `/api/captures/${capture.value.id}/save`,
      { name: result.value },
    );
    await reloadCaptures();
    ElMessage.success("记录已保存");
  } catch (error) {
    if (error !== "cancel" && error !== "close") failure(error);
  }
}
async function captureStopped() {
  await reloadCaptures();
  const item = captures.value.find((item) => item.id === capture.value?.id);
  if (item) {
    capture.value = item;
    await loadMessages();
  }
}
function onlyProtocol(row: Packet) {
  include.value = String(row.protocolId || row.protocolName || "");
  filter();
}
function excludeProtocol(row: Packet) {
  exclude.value = [exclude.value, row.protocolId || row.protocolName]
    .filter(Boolean)
    .join(",");
  filter();
}
const direction = ref("");
const status = ref("");
const connection = ref("");
const hideHeartbeat = ref(true);
const tab = ref("tree");
const dark = ref(localStorage.getItem("analyzer.dark") === "true");
const localPath = ref("");
const fileInput = ref<HTMLInputElement>();
const selectedRange = ref("");
const operation = ref({ name: "", from: 0, to: 0 });
const markDialog = ref(false);
const operationStart = ref<{ captureId: string; index: number }>();
async function beginOperation() {
  if (!capture.value) return;
  const id = capture.value.id;
  try {
    const item = await api<Capture>(`/api/captures/${id}/overview`);
    if (capture.value?.id !== id) return;
    operationStart.value = {
      captureId: item.id,
      index: (item.lastIndex ?? -1) + 1,
    };
    ElMessage.success("操作起点已记录，现在去游戏执行操作，再点击结束");
  } catch (error) {
    failure(error);
  }
}
async function endOperation() {
  if (
    !capture.value ||
    !operationStart.value ||
    operationStart.value.captureId !== capture.value.id
  )
    return;
  const id = capture.value.id;
  try {
    const item = await api<Capture>(`/api/captures/${id}/overview`);
    if (capture.value?.id !== id) return;
    if ((item.lastIndex ?? -1) < operationStart.value.index) {
      ElMessage.warning("操作后尚未收到消息，请稍后重试");
      return;
    }
    capture.value = item;
    operation.value = {
      name: "操作分析 · " + new Date().toLocaleString("sv-SE"),
      from: operationStart.value.index,
      to: item.lastIndex!,
    };
    markDialog.value = true;
  } catch (error) {
    failure(error);
  }
}
async function freezeOperation() {
  if (!capture.value || busy.value) return;
  const id = capture.value.id;
  busy.value = true;
  try {
    const frozen = await post<Capture>(
      `/api/captures/${id}/snapshot`,
      operation.value,
    );
    operationStart.value = undefined;
    markDialog.value = false;
    await reloadCaptures();
    if (capture.value?.id === id) await openCapture(frozen);
    ElMessage.success("分析快照已保存，原连接继续采集，可将本记录交给 AI 查询");
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
  }
}
const guideDialog = ref(false);
const errorText = ref("");
const messageGate = new RequestGate();
const detailGate = new RequestGate();
const detailCache = new Map<string, Packet>();
const autoFollow = ref(true);
const browsingHistory = ref(false);
const viewingTail = ref(false);
const unread = shallowRef<Record<string, number>>({});
const appliedSearch = ref("");
const selectedRowIndex = ref<number>();
let listReloading = false;
let pendingLive = false;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
const bodyText = computed(() => pretty(selected.value?.body));
const packetHexText = computed(() => hex(selected.value?.packetHex));
const wireHexText = computed(() => hex(selected.value?.wireHex));
const currentUnread = computed(() =>
  capture.value ? unread.value[capture.value.id] || 0 : 0,
);
const detailMetadata = computed(() => {
  if (!selected.value) return {};
  const { body, schema, packetHex, wireHex, ...metadata } = selected.value;
  return metadata;
});
const statusText: Record<string, string> = {
  DECODED: "已解码",
  PARTIAL: "部分解码",
  FAILED: "解码失败",
  UNMAPPED: "未映射",
};
function failure(error: unknown) {
  errorText.value = error instanceof Error ? error.message : String(error);
  ElMessage.error(errorText.value);
}
watch(
  dark,
  (value) => {
    document.documentElement.classList.toggle("dark", value);
    localStorage.setItem("analyzer.dark", String(value));
  },
  { immediate: true },
);
async function reloadCaptures() {
  captures.value = await api<Capture[]>("/api/captures");
}
async function refreshLive(
  connections: CaptureProgress[],
  changedIds: string[],
) {
  try {
    const known = new Set(captures.value.map((item) => item.id));
    // 只有新连接或连接结束时才重新读取记录目录；普通消息只更新已有摘要。
    if (
      !listReloading &&
      changedIds.some(
        (id) =>
          !known.has(id) || !connections.some((item) => item.captureId === id),
      )
    ) {
      listReloading = true;
      try {
        await reloadCaptures();
      } finally {
        listReloading = false;
      }
    }
    const progress = new Map(connections.map((item) => [item.captureId, item]));
    const nextUnread = { ...unread.value };
    captures.value = captures.value.map((item) => {
      const current = progress.get(item.id);
      if (
        !current ||
        (current.messages === (item.totalReceived ?? item.messageCount) &&
          (current.clearVersion || 0) === (item.clearVersion || 0))
      )
        return item;
      nextUnread[item.id] = shouldCountUnread(
        capture.value?.id,
        item.id,
        autoFollow.value,
        browsingHistory.value,
      )
        ? (nextUnread[item.id] || 0) +
          Math.max(
            0,
            current.messages - (item.totalReceived ?? item.messageCount),
          )
        : 0;
      return {
        ...item,
        totalReceived: current.messages,
        clearVersion: current.clearVersion || 0,
        clearedCount: current.clearedCount || 0,
        messageCount:
          current.retainedMessages ??
          Math.min(current.messages, item.maxMessages || current.messages),
        evictedCount: current.evictedCount ?? item.evictedCount,
      };
    });
    unread.value = nextUnread;
    if (
      shouldRefreshMessages(
        capture.value?.id,
        changedIds,
        autoFollow.value,
        browsingHistory.value,
      )
    ) {
      if (loading.value) pendingLive = true;
      else await loadMessages(true, true);
    }
  } catch (error) {
    failure(error);
  }
}
async function openLiveCapture(id: string) {
  let item = captures.value.find((value) => value.id === id);
  if (!item) {
    await reloadCaptures();
    item = captures.value.find((value) => value.id === id);
  }
  if (item) await openCapture(item);
}
async function loadMessages(tail = false, background = false) {
  if (!capture.value) return;
  const id = capture.value.id;
  const request = messageGate.begin();
  loading.value = true;
  interactiveLoading.value = !background;
  const params = new URLSearchParams({
    q: appliedSearch.value,
    protocolId: protocolId.value.trim(),
    order: order.value,
    include: appliedInclude.value,
    exclude: appliedExclude.value,
    direction: direction.value,
    status: status.value,
    connection: connection.value,
    hideHeartbeat: String(hideHeartbeat.value),
    offset: String((page.value - 1) * 100),
    limit: "100",
    tail: String(tail),
  });
  if (selectedRange.value) {
    const mark = capture.value.marks[Number(selectedRange.value) - 1];
    if (mark) {
      params.set("from", String(mark.from));
      params.set("to", String(mark.to));
    }
  }
  try {
    const result = await api<Page>(`/api/captures/${id}/messages?${params}`, {
      signal: request.signal,
    });
    if (!request.current() || capture.value?.id !== id) return;
    rows.value = reconcileRows(rows.value, result.items);
    total.value = result.total;
    capture.value = result.capture;
    // 已淘汰消息仍可暂留详情供阅读，但不冒充仍在当前缓存中。
    viewingTail.value = tail;
    if (
      selected.value &&
      selected.value.index < (result.capture.firstIndex || 0)
    ) {
      selectedRowIndex.value = undefined;
    }
    unread.value = { ...unread.value, [id]: 0 };
  } catch (error) {
    if (request.current()) failure(error);
  } finally {
    if (request.current()) {
      loading.value = false;
      interactiveLoading.value = false;
      if (pendingLive) {
        pendingLive = false;
        refreshTimer = setTimeout(() => {
          if (
            capture.value?.id === id &&
            autoFollow.value &&
            !browsingHistory.value
          )
            void loadMessages(true, true);
        }, 500);
      }
    }
  }
}
async function openCapture(item: Capture) {
  if (!item.id) {
    failure(new Error(item.error || "记录不可用"));
    return;
  }
  messageGate.cancel();
  detailGate.cancel();
  if (refreshTimer) clearTimeout(refreshTimer);
  pendingLive = false;
  capture.value = item;
  selected.value = undefined;
  selectedRowIndex.value = undefined;
  rows.value = [];
  browsingHistory.value = false;
  selectedRange.value = "";
  connection.value = "";
  page.value = 1;
  await loadMessages(autoFollow.value && item.liveState === "CAPTURING");
}
function filter() {
  appliedSearch.value = search.value.trim();
  appliedInclude.value = include.value.trim();
  appliedExclude.value = exclude.value.trim();
  localStorage.setItem("analyzer.order", order.value);
  page.value = 1;
  browsingHistory.value = false;
  void loadMessages(false);
}
function changePage(value: number) {
  browsingHistory.value = true;
  page.value = value;
  void loadMessages();
}
function browseHistory() {
  browsingHistory.value = true;
  page.value =
    order.value === "desc" ? 1 : Math.max(1, Math.ceil(total.value / 100));
  void loadMessages();
}
function jumpLatest() {
  browsingHistory.value = false;
  if (!autoFollow.value) autoFollow.value = true;
  else void loadMessages(true);
}
function captureName(item: Capture) {
  return item.name.startsWith("实时 · ")
    ? "实时 · " + (item.connections[0]?.id.slice(0, 8) || item.id.slice(0, 8))
    : item.name;
}
function neighbour(delta: number) {
  const index = rows.value.findIndex(
    (item) => item.index === selectedRowIndex.value,
  );
  const row = rows.value[index < 0 ? 0 : index + delta];
  if (row) void select(row);
}
async function select(row: Packet | undefined) {
  if (!row || !capture.value) return;
  // 用户开始读某条消息后暂停列表跟随，后台采集继续；“回到最新”再恢复。
  if (capture.value.liveState === "CAPTURING") autoFollow.value = false;
  const id = capture.value.id;
  const key = id + ":" + row.index;
  selectedRowIndex.value = row.index;
  const request = detailGate.begin();
  if (
    selected.value?.index === row.index &&
    selected.value.connection === row.connection
  )
    return;
  const cached = detailCache.get(key);
  if (cached) {
    selected.value = cached;
    return;
  }
  try {
    const detail = await api<Packet>(
      `/api/captures/${id}/messages/${row.index}`,
      { signal: request.signal },
    );
    if (request.current() && capture.value?.id === id) {
      selected.value = detail;
      // 仅缓存小消息详情，大包体不长期驻留；上限 24 条且单条不超过 128 KiB。
      if ((detail.bytes || 0) <= 128 * 1024) {
        detailCache.set(key, detail);
        if (detailCache.size > 24)
          detailCache.delete(detailCache.keys().next().value!);
      }
    }
  } catch (error) {
    if (request.current()) failure(error);
  }
}
watch(autoFollow, (value) => {
  if (
    value &&
    !browsingHistory.value &&
    capture.value?.liveState === "CAPTURING"
  )
    void loadMessages(true);
});
onBeforeUnmount(() => {
  messageGate.cancel();
  detailGate.cancel();
  if (refreshTimer) clearTimeout(refreshTimer);
});
async function imported(item: Capture) {
  await reloadCaptures();
  await openCapture(item);
  ElMessage.success(
    `已导入 ${item.messageCount} 条消息，已解码 ${item.counts.DECODED || 0} 条`,
  );
}
async function importPath() {
  if (!localPath.value.trim() || busy.value) return;
  busy.value = true;
  errorText.value = "";
  try {
    await imported(
      await post<Capture>("/api/import/path", { path: localPath.value.trim() }),
    );
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
  }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || busy.value) return;
  if (file.size > 128 * 1024 * 1024) {
    failure(new Error("文件超过 128 MiB"));
    input.value = "";
    return;
  }
  busy.value = true;
  errorText.value = "";
  try {
    await imported(
      await api<Capture>(
        "/api/import/saz?name=" + encodeURIComponent(file.name),
        { method: "POST", body: file },
      ),
    );
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
    input.value = "";
  }
}
async function mark() {
  if (!capture.value || busy.value) return;
  busy.value = true;
  try {
    capture.value = await post<Capture>(
      `/api/captures/${capture.value.id}/marks`,
      operation.value,
    );
    markDialog.value = false;
    await reloadCaptures();
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
  }
}
function openMark() {
  operation.value = {
    name: "",
    from: selected.value?.index ?? capture.value?.firstIndex ?? 0,
    to:
      selected.value?.index ??
      capture.value?.lastIndex ??
      Math.max(0, (capture.value?.messageCount || 1) - 1),
  };
  markDialog.value = true;
}
async function exportCapture() {
  if (!capture.value) return;
  try {
    const data = await api(`/api/captures/${capture.value.id}/export`);
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = capture.value.name.replace(/\.saz$/i, "") + "-decoded.json";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    failure(error);
  }
}
function pretty(value: unknown) {
  return JSON.stringify(value, null, 2) ?? "";
}
function hex(value?: string) {
  const bytes = (value || "").match(/.{2}/g) || [];
  const lines: string[] = [];
  for (let index = 0; index < bytes.length; index += 16)
    lines.push(
      index.toString(16).padStart(8, "0") +
        "  " +
        bytes
          .slice(index, index + 16)
          .join(" ")
          .toUpperCase(),
    );
  return lines.join("\n");
}
function time(value: string) {
  if (!value) return "未知";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString("zh-CN", {
        hour12: false,
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        fractionalSecondDigits: 3,
      });
}
async function copy() {
  try {
    await navigator.clipboard.writeText(
      pretty(selected.value?.body ?? selected.value),
    );
    ElMessage.success("已复制");
  } catch (error) {
    failure(error);
  }
}
onMounted(async () => {
  try {
    health.value = await api("/api/health");
    settings.value = await api("/api/settings");
    if (!health.value.catalog.messageCount || settings.value.catalogError) {
      selectedProtocolFile.value = settings.value.protocolFile || "";
      settingsDialog.value = true;
    }
    await reloadCaptures();
    const first = captures.value.find((item) => item.id);
    if (first) await openCapture(first);
  } catch (error) {
    failure(error);
  }
});
</script>
<template>
  <div class="workbench">
    <header class="topbar">
      <div class="brand">
        <span class="brand-icon">⇄</span>
        <div>ProtoLens<small>游戏协议分析工作台</small></div>
      </div>
      <span class="divider"></span>
      <span class="state-dot" :class="{ online: health.ready }"></span
      ><span class="muted">{{
        health.ready ? "本机服务已连接" : "连接后端中"
      }}</span>
      <el-tag size="small" effect="plain">Thrift Compact</el-tag>
      <span class="muted"
        >{{ health.catalog.messageCount.toLocaleString() }} 条协议</span
      >
      <span
        v-if="settings.serverPort"
        class="muted"
        :title="settings.configFile"
        >后端 :{{ settings.serverPort }} · 页面 :{{ settings.frontendPort }} ·
        代理 :{{ settings.collectorPort }}</span
      >
      <div class="topbar-right">
        <AiAnalysisPanel
          :capture="capture"
          :selected-index="selectedRowIndex"
        />
        <el-button text @click="openSettings">设置 / 协议加载</el-button>
        <el-button text @click="checkUpdate">检查更新</el-button>
        <el-button
          v-if="update.available && update.downloadUrl"
          text
          :loading="updateBusy"
          @click="downloadUpdate"
          >下载 {{ update.latestVersion }}</el-button
        >
        <el-button text @click="guideDialog = true">使用说明</el-button
        ><el-switch
          v-model="dark"
          inline-prompt
          active-text="深色"
          inactive-text="浅色"
        />
      </div>
    </header>
    <LiveCapturePanel
      ref="livePanel"
      v-model:follow="autoFollow"
      :active-capture-id="capture?.id"
      @changed="refreshLive"
      @open="openLiveCapture"
      @stopped="captureStopped"
      @cleared="sessionCleared"
    />
    <div v-if="errorText" class="error-bar" role="alert">
      {{ errorText }}<el-button text @click="errorText = ''">关闭</el-button>
    </div>
    <div
      v-if="health.ready && !health.catalog.messageCount"
      class="warning-bar"
    >
      协议字典尚未加载。点击右上角“设置 / 协议加载”，填写竞品目录并加载。
    </div>
    <div class="workspace">
      <aside class="sidebar">
        <div class="section-heading">
          抓包记录
          <el-button text size="small" @click="reloadCaptures().catch(failure)"
            >刷新</el-button
          >
        </div>
        <div class="capture-list">
          <div
            role="button"
            tabindex="0"
            @keydown.enter.self="openCapture(item)"
            v-for="item in captures"
            :key="item.id || item.name"
            class="capture-item"
            :class="{ active: capture?.id === item.id }"
            :title="item.name"
            @click="openCapture(item)"
          >
            <el-button
              class="delete-capture"
              text
              size="small"
              type="danger"
              :disabled="item.liveState === 'CAPTURING'"
              @click.stop="deleteCapture(item)"
              title="删除记录"
              >删除</el-button
            >
            <strong>{{ captureName(item) }}</strong
            ><small v-if="item.id"
              >{{ item.messageCount.toLocaleString() }} 条消息 ·
              {{ item.temporary ? "临时" : "已保存" }} ·
              {{ item.liveState === "CAPTURING" ? "采集中" : "已结束" }}
              <span v-if="unread[item.id]" class="new-count"
                >+{{ unread[item.id] }}</span
              ></small
            ><small v-else>{{ item.error }}</small>
          </div>
          <div v-if="!captures.length" class="muted empty-small">
            尚无抓包记录<br />导入 SAZ 后保存在本机
          </div>
        </div>
        <div class="sidebar-bottom">
          <b>本地导入</b>
          <p class="muted">一次导出整个 WebSocket 会话，批量解析双向消息。</p>
          <el-button type="primary" :loading="busy" @click="fileInput?.click()"
            >选择 SAZ 文件</el-button
          ><input
            ref="fileInput"
            type="file"
            accept=".saz"
            hidden
            @change="upload"
          /><el-input
            v-model="localPath"
            placeholder="或填写本机 SAZ 绝对路径"
            :disabled="busy"
            @keyup.enter="importPath"
          /><el-button
            :loading="busy"
            :disabled="!localPath.trim()"
            @click="importPath"
            >从路径导入</el-button
          >
        </div>
      </aside>
      <main class="main-area">
        <div class="capture-bar">
          <div>
            <strong :title="capture?.name">{{
              capture ? captureName(capture) : "选择或导入一份抓包"
            }}</strong
            ><span v-if="capture" class="muted"
              >缓存 {{ capture.messageCount }} 条 · 解码
              {{ capture.counts.DECODED || 0 }} · 部分
              {{ capture.counts.PARTIAL || 0 }} · 失败
              {{ capture.counts.FAILED || 0 }}</span
            >
            <span v-if="capture?.evictedCount" class="reading-hint"
              >已淘汰 {{ capture.evictedCount }} 条旧消息；# 保持原始编号</span
            >
            <span v-if="browsingHistory" class="reading-hint"
              >翻阅历史中 · 新消息不会改变当前位置</span
            >
          </div>
          <div>
            <el-button
              v-if="capture?.temporary && capture.liveState !== 'CAPTURING'"
              type="primary"
              @click="saveCapture"
              >保存记录</el-button
            >
            <el-button
              v-if="
                capture?.liveState === 'CAPTURING' &&
                operationStart?.captureId !== capture.id
              "
              @click="beginOperation"
              >开始标记操作</el-button
            >
            <el-button
              v-if="capture && operationStart?.captureId === capture.id"
              type="primary"
              @click="endOperation"
              >结束并选取区间</el-button
            >
            <el-button :disabled="!capture" @click="openMark"
              >标记操作区间</el-button
            ><el-button :disabled="!capture" @click="exportCapture"
              >导出解码 JSON</el-button
            >
          </div>
        </div>
        <div
          class="filters"
          v-memo="[
            search,
            direction,
            status,
            connection,
            hideHeartbeat,
            order,
            showAdvancedColumns,
            capture?.id,
            capture?.liveState,
            currentUnread,
            appliedInclude,
            appliedExclude,
            livePanel?.canClearSession,
            livePanel?.clearing,
          ]"
        >
          <el-input
            v-model="search"
            clearable
            placeholder="搜索协议名、编号、字段或实际值"
            @keyup.enter="filter"
            @clear="filter"
          /><el-input
            v-model="protocolId"
            clearable
            placeholder="协议 ID"
            @keyup.enter="filter"
            @clear="filter"
          />
          <el-select
            v-model="direction"
            placeholder="全部方向"
            clearable
            @change="filter"
            ><el-option label="发送 CS" value="CS" /><el-option
              label="接收 SC"
              value="SC" /></el-select
          ><el-select
            v-model="status"
            placeholder="全部状态"
            clearable
            @change="filter"
            ><el-option
              v-for="(label, key) in statusText"
              :key="key"
              :label="label"
              :value="key" /></el-select
          ><el-select
            v-model="connection"
            placeholder="全部连接"
            clearable
            @change="filter"
            ><el-option
              v-for="item in capture?.connections || []"
              :key="item.id"
              :value="item.id"
              :label="item.id + ' · ' + item.endpoint" /></el-select
          ><el-checkbox v-model="hideHeartbeat" @change="filter"
            >隐藏心跳</el-checkbox
          ><el-button type="primary" @click="filter">查询</el-button>
          <el-button
            v-if="capture?.liveState === 'CAPTURING'"
            :type="currentUnread ? 'primary' : 'default'"
            plain
            class="jump-latest"
            @click="jumpLatest"
            >{{
              currentUnread ? `${currentUnread} 条新增 · 回到最新` : "回到最新"
            }}</el-button
          >
          <el-select v-model="order" @change="filter" aria-label="消息顺序">
            <el-option label="最新在前 ↓" value="desc" /><el-option
              label="最早在前 ↑"
              value="asc"
            />
          </el-select>
          <el-button @click="filterDialog = true"
            >协议过滤{{
              appliedInclude || appliedExclude ? " · 已启用" : ""
            }}</el-button
          >
          <el-checkbox v-model="showAdvancedColumns">序号 / 连接</el-checkbox>
          <el-button
            class="clear-session"
            :disabled="!livePanel?.canClearSession"
            :loading="livePanel?.clearing"
            title="清空本次采集全部未保存记录的消息，保留记录和连接"
            @click="livePanel?.clearSession()"
            >清空会话</el-button
          >
        </div>
        <div v-if="capture?.marks.length" class="marks">
          <span class="muted">操作区间</span
          ><el-select
            v-model="selectedRange"
            clearable
            placeholder="全部消息"
            @change="filter"
            ><el-option
              v-for="(item, index) in capture.marks"
              :key="index"
              :label="`${item.expired ? '[部分已淘汰] ' : ''}${item.name} [${item.from}–${item.to}]`"
              :value="String(index + 1)" /></el-select
          ><span class="muted"
            >区间用于回看操作，推送因果关系需结合客户端代码确认。</span
          >
        </div>
        <SplitPane
          direction="vertical"
          label="调整消息与详情高度"
          storage-key="analyzer.split"
          :minimum-primary="160"
          :minimum-secondary="190"
          :initial-ratio="0.52"
        >
          <template #primary>
            <section class="message-panel">
              <el-table
                class="packet-table"
                v-loading="interactiveLoading || busy"
                :data="rows"
                :fit="true"
                :current-row-key="selectedRowIndex"
                height="100%"
                highlight-current-row
                empty-text="暂无消息，先导入包含 WebSocket 数据的 SAZ"
                @row-click="select"
                row-key="index"
                size="small"
              >
                <el-table-column prop="index" label="#" width="64" />
                <el-table-column label="时间" width="124"
                  ><template #default="{ row }">{{
                    time(row.time)
                  }}</template></el-table-column
                >
                <el-table-column label="方向" width="68"
                  ><template #default="{ row }"
                    ><span
                      class="direction"
                      :class="row.direction.toLowerCase()"
                      >{{ row.direction === "CS" ? "↑ CS" : "↓ SC" }}</span
                    ></template
                  ></el-table-column
                >
                <el-table-column
                  prop="protocolName"
                  label="协议"
                  width="300"
                  show-overflow-tooltip
                  ><template #default="{ row }"
                    ><button
                      class="protocol-name protocol-button"
                      :title="row.protocolName"
                      @click.stop="select(row)"
                    >
                      {{ row.protocolName || "未识别消息" }}
                    </button></template
                  ></el-table-column
                >
                <el-table-column
                  prop="preview"
                  label="消息内容摘要"
                  min-width="240"
                  show-overflow-tooltip
                />
                <el-table-column label="过滤" width="100"
                  ><template #default="{ row }">
                    <el-button link size="small" @click.stop="onlyProtocol(row)"
                      >仅看</el-button
                    >
                    <el-button
                      link
                      size="small"
                      @click.stop="excludeProtocol(row)"
                      >排除</el-button
                    >
                  </template></el-table-column
                >
                <el-table-column prop="protocolId" label="编号" width="84" />
                <el-table-column label="状态" width="92"
                  ><template #default="{ row }"
                    ><span
                      class="decode-state"
                      :class="row.status.toLowerCase()"
                      >{{ statusText[row.status] || row.status }}</span
                    ></template
                  ></el-table-column
                >
                <el-table-column
                  v-if="showAdvancedColumns"
                  prop="sequence"
                  label="序号"
                  width="76"
                />
                <el-table-column label="字节" width="76"
                  ><template #default="{ row }">{{
                    row.inputFormat === "DECOMPRESSED_MESSAGE"
                      ? row.bytes
                      : row.wireBytes
                  }}</template></el-table-column
                >
                <el-table-column
                  v-if="showAdvancedColumns"
                  label="连接"
                  width="112"
                  show-overflow-tooltip
                  ><template #default="{ row }"
                    ><span :title="row.connection">{{
                      row.connection.slice(0, 8)
                    }}</span></template
                  ></el-table-column
                >
              </el-table>
              <div class="pagination">
                <span class="muted">{{
                  viewingTail
                    ? `最新 ${rows.length} 条 / 匹配 ${total} 条`
                    : `匹配 ${total} 条 · 时间按本机时区显示`
                }}</span>
                <el-button
                  v-if="viewingTail"
                  text
                  size="small"
                  @click="browseHistory"
                  >翻阅历史</el-button
                >
                <el-pagination
                  v-else
                  v-model:current-page="page"
                  :page-size="100"
                  :total="total"
                  layout="total, prev, pager, next"
                  size="small"
                  @current-change="changePage"
                />
              </div>
            </section>
          </template>
          <template #secondary>
            <section class="detail-panel">
              <div class="detail-heading">
                <b>{{ selected?.protocolName || "消息详情" }}</b
                ><span v-if="selected?.protocolId" class="muted"
                  >#{{ selected.protocolId }} · {{ selected.direction }} ·
                  {{ selected.bytes }} 字节（解压后）</span
                ><el-button v-if="selected" text size="small" @click="copy"
                  >复制 JSON</el-button
                >
                <el-button
                  v-if="selected"
                  text
                  size="small"
                  @click="neighbour(-1)"
                  :disabled="
                    rows.findIndex((item) => item.index === selectedRowIndex) <=
                    0
                  "
                  >上一条</el-button
                >
                <el-button
                  v-if="selected"
                  text
                  size="small"
                  @click="neighbour(1)"
                  :disabled="
                    rows.findIndex((item) => item.index === selectedRowIndex) >=
                    rows.length - 1
                  "
                  >下一条</el-button
                >
              </div>
              <div v-if="selected?.error" class="error-bar">
                {{ selected.error }}
              </div>
              <div v-if="selected?.warnings?.length" class="warning-bar">
                {{ selected.warnings.join("；") }}
              </div>
              <el-tabs v-model="tab" class="detail-tabs">
                <el-tab-pane label="JSON 树" name="tree"
                  ><div v-if="tab === 'tree'" class="detail-content">
                    <JsonTree v-if="selected?.body" :value="selected.body" />
                    <div v-else class="empty-detail">
                      {{
                        selected
                          ? "当前消息未能完整解码，请查看原始包与失败原因。"
                          : "选中一条消息，查看协议字段与实际值"
                      }}
                    </div>
                  </div></el-tab-pane
                >
                <el-tab-pane label="JSON 文本" name="json">
                  <pre v-if="tab === 'json'" class="detail-content">{{
                    bodyText
                  }}</pre>
                </el-tab-pane>
                <el-tab-pane label="原始包体" name="hex"
                  ><div v-if="tab === 'hex'" class="detail-content">
                    <p class="muted">
                      解压后的完整业务包（消息头 + Thrift Compact
                      消息体；头长见元数据 headerBytes）
                    </p>
                    <pre>{{ packetHexText }}</pre>
                    <p class="muted">
                      {{
                        selected?.inputFormat === "DECOMPRESSED_MESSAGE"
                          ? "实时采集器已解压并重组消息，没有提供原始 wire 帧；上方保留的是完整业务包。"
                          : "SAZ 中的原始 WebSocket 帧（包含帧头、掩码与压缩字节）"
                      }}
                    </p>
                    <pre>{{ wireHexText }}</pre>
                  </div></el-tab-pane
                >
                <el-tab-pane label="字段定义" name="schema"
                  ><div v-if="tab === 'schema'" class="detail-content">
                    <p class="muted">
                      来源：{{ selected?.catalog?.sourceFile
                      }}<span v-if="selected?.definition?.line"
                        >:{{ selected.definition.line }}</span
                      >
                      · 字典 SHA256 {{ selected?.catalog?.sha256.slice(0, 16) }}
                    </p>
                    <p class="muted">
                      字段定义格式：[字段名, Thrift 类型]；2 BOOL · 8 I32 · 10
                      I64 · 11 STRING · [12,结构名] STRUCT · [15,元素类型] LIST
                      · [13,键类型,值类型] MAP
                    </p>
                    <JsonTree
                      v-if="selected?.schema"
                      :value="selected.schema"
                      label="字段号"
                    /></div
                ></el-tab-pane>
                <el-tab-pane label="元数据" name="metadata">
                  <pre v-if="tab === 'metadata'" class="detail-content">{{
                    pretty(detailMetadata)
                  }}</pre>
                </el-tab-pane>
              </el-tabs>
            </section>
          </template>
        </SplitPane>
      </main>
    </div>
    <el-dialog
      v-model="markDialog"
      title="标记区间 / 保存 AI 分析快照"
      width="500px"
      ><p class="muted">
        以列表 #
        列为边界，保存一次操作前后的消息范围。区间不自动证明请求与推送的因果关系。“保存分析快照”会复制并保存该区间和字典，原连接继续采集；已淘汰的消息无法补回。
      </p>
      <el-form label-width="90px"
        ><el-form-item label="操作名称"
          ><el-input
            v-model="operation.name"
            maxlength="100"
            placeholder="例如：联盟科技捐献一次" /></el-form-item
        ><el-form-item label="起始消息 #"
          ><el-input-number
            v-model="operation.from"
            :min="capture?.firstIndex || 0"
            :max="
              capture?.lastIndex ??
              Math.max(0, (capture?.messageCount || 1) - 1)
            " /></el-form-item
        ><el-form-item label="结束消息 #"
          ><el-input-number
            v-model="operation.to"
            :min="operation.from"
            :max="
              capture?.lastIndex ??
              Math.max(0, (capture?.messageCount || 1) - 1)
            " /></el-form-item></el-form
      ><template #footer
        ><el-button @click="markDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="mark"
          >保存区间</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="filterDialog" title="协议过滤" width="540px">
      <p class="muted">
        名称或编号支持多个条件，用逗号分隔；同组任一匹配，排除优先。与搜索、方向、状态和操作区间同时生效。
      </p>
      <el-form label-width="90px">
        <el-form-item label="仅包含"
          ><el-input
            v-model="include"
            placeholder="例如：Donate,2361"
            clearable
        /></el-form-item>
        <el-form-item label="排除"
          ><el-input
            v-model="exclude"
            placeholder="例如：MapAppear,SynchronizeTime"
            clearable
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button
          @click="
            include = '';
            exclude = '';
            filter();
            filterDialog = false;
          "
          >清除过滤</el-button
        ><el-button
          type="primary"
          @click="
            filter();
            filterDialog = false;
          "
          >应用</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="settingsDialog"
      title="采集设置与竞品协议"
      width="680px"
    >
      <p class="muted">
        修改前请停止采集。路径和缓存设置保存在本机，启动时自动加载；历史记录保留各自的协议字典。
      </p>
      <el-descriptions
        :column="1"
        size="small"
        border
        style="margin-bottom: 16px"
      >
        <el-descriptions-item label="后端服务">{{
          settings.backendAddress
        }}</el-descriptions-item>
        <el-descriptions-item label="页面地址">{{
          settings.browserAddress
        }}</el-descriptions-item>
        <el-descriptions-item label="采集代理">{{
          settings.proxyAddress
        }}</el-descriptions-item>
        <el-descriptions-item label="启动配置"
          ><span style="overflow-wrap: anywhere">{{
            settings.configFile
          }}</span></el-descriptions-item
        >
      </el-descriptions>
      <p class="muted">
        端口在上述配置文件修改，重启项目生效；页面不会另外保存一套端口。
      </p>
      <el-tabs v-model="settingsTab">
        <el-tab-pane label="协议与缓存" name="protocol">
          <p v-if="settings.catalogError" class="error-bar">
            {{ settings.catalogError }}
          </p>
          <el-form label-width="120px">
            <el-form-item label="目录 / 协议文件"
              ><el-input
                v-model="settings.competitorPath"
                placeholder="客户端根目录、任意子目录，或 thrift_data.js 完整路径"
                @input="sourceChanged"
            /></el-form-item>
            <el-form-item v-if="protocolCandidates.length" label="候选协议文件">
              <el-select
                v-model="selectedProtocolFile"
                placeholder="找到多份，请选择一份"
                style="width: 100%"
              >
                <el-option
                  v-for="item in protocolCandidates"
                  :key="item.path"
                  :value="item.path"
                  :label="`${item.relativePath} · ${Math.ceil(item.sizeBytes / 1024)} KB`"
                  :title="item.path"
                />
              </el-select>
            </el-form-item>
            <el-form-item
              ><el-button :loading="settingsBusy" @click="findProtocolFiles"
                >查找协议文件</el-button
              ><el-button
                type="primary"
                :loading="settingsBusy"
                @click="reloadCatalog"
                >加载协议并记住路径</el-button
              ><span class="muted"
                >当前 {{ health.catalog.messageCount }} 条协议</span
              ></el-form-item
            >
            <el-form-item
              v-if="settings.loadedProtocolFile"
              label="当前加载文件"
            >
              <span class="muted" style="overflow-wrap: anywhere">{{
                settings.loadedProtocolFile
              }}</span>
            </el-form-item>
            <el-form-item label="每连接消息上限"
              ><el-input-number
                v-model="settings.maxMessages"
                :min="100"
                :max="50000"
                :step="1000"
            /></el-form-item>
            <el-form-item label="每连接容量 MiB"
              ><el-input-number
                v-model="settings.maxCacheMiB"
                :min="1"
                :max="256"
            /></el-form-item>
          </el-form>
          <p class="muted">
            超过任一上限淘汰最旧消息，序号不重用。临时记录退出后不保留；停止时或停止后可另行保存。
          </p>
        </el-tab-pane>
        <el-tab-pane label="采集目标" name="collector">
          <CollectorSettingsPanel
            v-if="settingsDialog && settingsTab === 'collector'"
          />
        </el-tab-pane>
      </el-tabs>
      <template #footer
        ><el-button @click="settingsDialog = false">关闭</el-button
        ><el-button
          v-if="settingsTab === 'protocol'"
          type="primary"
          :loading="settingsBusy"
          @click="saveSettings"
          >保存缓存设置</el-button
        ></template
      >
    </el-dialog>
    <el-dialog v-model="guideDialog" title="从抓包到可读协议" width="650px"
      ><ol class="guide">
        <li>
          先在 Fiddler 开启 System Proxy，再启动小游戏，保留主游戏 WebSocket
          从握手开始的全部消息。
        </li>
        <li>
          选中主游戏连接，导出 SAZ；在左侧选择文件或填写本机路径批量导入。
        </li>
        <li>
          在协议搜索输入 Donate 或 2364，查看科技捐献。CS 是客户端发送，SC
          是服务端下发。
        </li>
        <li>
          选中消息查看 JSON 树、完整字节及字段字典；I64 使用字符串保留精度，Map
          用 $map 条目数组保存原始键。
        </li>
        <li>
          标记一段操作区间，便于回看请求、响应和交错推送。当前工具不会自动认定相邻消息的因果关系。
        </li>
      </ol>
      <p class="muted">
        支持离线 SAZ 导入和独立 mitmproxy
        实时采集，详见顶部工具栏。工具不会向游戏服务器发送或重放请求。客户端更新后重新提取字典，再重启服务并重新导入；旧记录保留当时的字典指纹。
      </p></el-dialog
    >
  </div>
</template>
