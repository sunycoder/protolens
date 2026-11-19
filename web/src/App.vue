<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import { ElMessage, ElMessageBox, type TableInstance } from "element-plus";
import JsonTree from "./JsonTree.vue";
import SplitPane from "./SplitPane.vue";
import LiveCapturePanel from "./LiveCapturePanel.vue";
import AiAnalysisPanel from "./AiAnalysisPanel.vue";
import CollectorSettingsPanel from "./CollectorSettingsPanel.vue";
import MessageComparison from "./MessageComparison.vue";
import HighlightedText from "./HighlightedText.vue";
import MessageRowActions from "./MessageRowActions.vue";
import ProtocolOverview from "./ProtocolOverview.vue";
import TextPreview from "./TextPreview.vue";
import { useQueryResults } from "./useQueryResults";
import { useMessageSelection } from "./useMessageSelection";
import {
  readStored,
  writeStored,
  validQuery,
  validPlace,
  patchQuery,
  type ReadingPlace,
  type SavedView,
} from "./workspaceSession";
import {
  protocolChoice,
  mergeProtocolExclusions,
  toggleProtocolChoices,
  type ProtocolChoice,
} from "./protocolSelection";
import {
  defaultColumns,
  defaultQuery,
  readColumns,
  listMinimumWidth,
  chooseDetailLayout,
  contextQuery,
  contextRankParams,
  allowReadingShortcut,
  type WorkspaceQuery,
  type DetailLayout,
} from "./workspaceInteractions";
import {
  findFieldMatches,
  neighbourPage,
  pageContaining,
} from "./analysisWorkspace";
import { api, post, type Capture, type Packet, type Page } from "./types";
import {
  reconcileRows,
  shouldRefreshMessages,
  shouldCountUnread,
  type CaptureProgress,
} from "./workspaceRefresh";

const livePanel = ref<InstanceType<typeof LiveCapturePanel>>();
const packetTable = ref<TableInstance>();
const rowActions = ref<InstanceType<typeof MessageRowActions>>();
const batchExcluding = ref(false);
const selectedProtocols = shallowRef<ProtocolChoice[]>([]);
const captures = shallowRef<Capture[]>([]);
const capture = shallowRef<Capture>();
const {
  rows,
  total,
  page,
  pageOffset,
  displayedPage,
  displayedQuery,
  pendingQuery,
  queryError,
  displayedAt,
  queryPublication,
  messageGate,
  queryMetrics,
} = useQueryResults();
const {
  selected,
  selectedRowIndex,
  detailLoading,
  detailError,
  detailTarget,
  detailOpen,
  detailGate,
  detailCache,
  detailMs,
  selectDetail,
} = useMessageSelection(
  () => capture.value,
  () => pauseListForInteraction(),
);
const expandedTags = ref(false);
const columns = ref(readColumns(localStorage.getItem("analyzer.columns.v3")));
const tableRevision = ref(0);
const splitPane = ref<InstanceType<typeof SplitPane>>();
const dataRegion = ref<HTMLElement>();
const regionSize = shallowRef({
  width: window.innerWidth,
  height: window.innerHeight - 190,
});
let regionObserver: ResizeObserver | undefined;
const busy = ref(false);
const loading = ref(false);
const interactiveLoading = ref(false);
const sidebarCollapsed = ref(
  localStorage.getItem("analyzer.sidebarCollapsed") !== "false",
);
const focusMode = ref(false);
const savedDock = localStorage.getItem("analyzer.layoutPreference.v3");
const detailDock = ref<DetailLayout>(
  ["auto", "right", "bottom", "overlay"].includes(savedDock || "")
    ? (savedDock as DetailLayout)
    : "auto",
);
const listWidth = computed(
  () => listMinimumWidth(columns.value) + (batchExcluding.value ? 36 : 0),
);
const effectiveLayout = computed(() =>
  chooseDetailLayout(
    regionSize.value.width,
    regionSize.value.height,
    detailDock.value,
    listWidth.value,
  ),
);
const detailDirection = computed(() =>
  effectiveLayout.value === "bottom" ? "vertical" : "horizontal",
);
const sidebarVisible = computed(
  () => !focusMode.value && (!sidebarCollapsed.value || !capture.value),
);
const detailVisible = computed(() => detailOpen.value && !focusMode.value);
function measureWorkspace() {
  const rect = dataRegion.value?.getBoundingClientRect();
  if (rect)
    regionSize.value = {
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    };
}
watch(sidebarCollapsed, (value) =>
  localStorage.setItem("analyzer.sidebarCollapsed", String(value)),
);
watch(detailDock, (value) =>
  localStorage.setItem("analyzer.layoutPreference.v3", value),
);
watch(
  columns,
  (value) => localStorage.setItem("analyzer.columns.v3", JSON.stringify(value)),
  { deep: true },
);
watch([detailDirection, detailVisible, sidebarVisible], async () => {
  const top = tableScrollTop();
  await nextTick();
  packetTable.value?.doLayout();
  packetTable.value?.setScrollTop(top);
});
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
const messageIndex = ref("");
const appliedProtocolId = ref("");
const appliedMessageIndex = ref("");
const jumpTarget = ref<number>();
const locatedRow = ref<number>();
const advancedFilters = ref(false);
const tableSettings = ref(false);
const comparisonBase = shallowRef<Packet>();
const comparisonDialog = ref(false);
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
  const choice = protocolChoice(row);
  if (choice) void quickQuery({ include: choice.token, protocolId: "" });
}
function excludeProtocol(row: Packet) {
  const choice = protocolChoice(row);
  if (!choice) return;
  const shown = pendingQuery.value || displayedQuery.value || currentQuery();
  void quickQuery({
    exclude: mergeProtocolExclusions(shown.exclude, [choice]),
  });
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
const operation = ref({ name: "", from: 0, to: 0, context: "" });
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
      context: "",
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
    if (capture.value?.id === id) {
      applyQuery({ ...defaultQuery, hideHeartbeat: false });
      await openCapture(frozen);
    }
    ElMessage.success("分析快照已保存，原连接继续采集，可将本记录交给 AI 查询");
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
  }
}
const workspaceView = ref<"messages" | "protocols">("messages");
watch(workspaceView, (view) => {
  if (view === "protocols") pauseListForInteraction();
});
const snapshotBusy = ref(false);
const continuousBusy = ref(false);
const savedSnapshot = ref<Capture>();
const comparingPrevious = ref(false);
const undoPlace = shallowRef<ReadingPlace>();
const savedViews = ref<SavedView[]>(
  readStored<unknown>("analyzer.views.v1", []) instanceof Array
    ? readStored<SavedView[]>("analyzer.views.v1", [])
        .filter(
          (view) =>
            view &&
            typeof view.name === "string" &&
            typeof view.dictionary === "string" &&
            validQuery(view.query),
        )
        .slice(0, 12)
    : [],
);
const viewDialog = ref(false);
let restoringSession = true;

function draftQuery(): WorkspaceQuery {
  return {
    ...currentQuery(),
    q: search.value.trim(),
    protocolId: protocolId.value.trim(),
    messageIndex: messageIndex.value.trim(),
    include: include.value.trim(),
    exclude: exclude.value.trim(),
  };
}
const hasDraft = computed(
  () =>
    !!displayedQuery.value &&
    JSON.stringify(draftQuery()) !== JSON.stringify(displayedQuery.value),
);
function readingPlace(): ReadingPlace | undefined {
  if (!capture.value || !displayedQuery.value) return;
  return {
    captureId: capture.value.id,
    query: { ...displayedQuery.value },
    page: displayedPage.value,
    selectedIndex: selectedRowIndex.value,
    scrollTop: tableScrollTop(),
    detailOpen: detailOpen.value,
    tab: tab.value,
  };
}
function saveReadingPlace() {
  if (restoringSession || loading.value) return;
  const place = readingPlace();
  if (place) writeStored("analyzer.reading.v1", place);
}
/** 保留输入草稿；只在查询成功后发布快捷操作及撤销位置。 */
async function quickQuery(patch: Partial<WorkspaceQuery>) {
  if (!capture.value) return;
  const before = displayedQuery.value || currentQuery();
  const base = pendingQuery.value || before;
  const draft = draftQuery();
  const previous = readingPlace();
  const next = patchQuery(base, patch);
  pauseListForInteraction();
  browsingHistory.value = true;
  await loadMessages(false, false, {
    query: next,
    page: 1,
    onCommit: () => {
      applyQuery(next);
      if (draft.q !== before.q) search.value = draft.q;
      if (draft.protocolId !== before.protocolId)
        protocolId.value = draft.protocolId;
      if (draft.messageIndex !== before.messageIndex)
        messageIndex.value = draft.messageIndex;
      if (draft.include !== before.include) include.value = draft.include;
      if (draft.exclude !== before.exclude) exclude.value = draft.exclude;
      undoPlace.value = previous;
      workspaceView.value = "messages";
    },
  });
}
async function undoQuickQuery() {
  const place = undoPlace.value;
  if (!place || place.captureId !== capture.value?.id) return;
  pauseListForInteraction();
  await loadMessages(false, false, {
    query: place.query,
    page: place.page,
    index: place.selectedIndex,
    scrollTop: place.scrollTop,
    onCommit: () => {
      applyQuery(place.query);
      undoPlace.value = undefined;
    },
  });
  if (!queryError.value && capture.value?.id === place.captureId) {
    detailOpen.value = place.detailOpen;
    tab.value = place.tab;
  }
}
function overviewSelection(rows: Packet[], action: "only" | "exclude") {
  const choices = rows
    .map(protocolChoice)
    .filter((item): item is ProtocolChoice => !!item);
  const base = pendingQuery.value || displayedQuery.value || currentQuery();
  void quickQuery(
    action === "only"
      ? { include: mergeProtocolExclusions("", choices), protocolId: "" }
      : { exclude: mergeProtocolExclusions(base.exclude, choices) },
  );
}
async function saveCurrentSnapshot() {
  const source = capture.value;
  if (!source || snapshotBusy.value) return;
  try {
    const result = await ElMessageBox.prompt(
      "保存当前仍保留的全部消息，源连接继续采集；已淘汰消息不能补回。",
      "保存当前快照",
      {
        inputValue: source.name.slice(0, 70) + " · 快照",
        inputValidator: (value) =>
          (!!value?.trim() && value.length <= 100) || "请输入 1～100 字名称",
      },
    );
    snapshotBusy.value = true;
    savedSnapshot.value = await post<Capture>(
      `/api/captures/${source.id}/snapshot-current`,
      { name: result.value },
    );
    await reloadCaptures();
    ElMessage.success("快照已保存，当前阅读位置和源连接保持");
  } catch (error) {
    if (error !== "cancel" && error !== "close") failure(error);
  } finally {
    snapshotBusy.value = false;
  }
}
async function enableContinuous() {
  const source = capture.value;
  if (!source || continuousBusy.value) return;
  continuousBusy.value = true;
  try {
    const result = await post<Capture>(
      `/api/captures/${source.id}/continuous`,
      {},
    );
    if (capture.value?.id === source.id) capture.value = result;
    await reloadCaptures();
    ElMessage.success(
      "已启用持续落盘；内存继续按预算淘汰，磁盘记录可在停止或重启后读取",
    );
  } catch (error) {
    failure(error);
  } finally {
    continuousBusy.value = false;
  }
}
async function comparePrevious() {
  const target = selected.value;
  const id = capture.value?.id;
  if (!target || !id || comparingPrevious.value) return;
  comparingPrevious.value = true;
  try {
    const previous = await api<Packet>(
      `/api/captures/${id}/previous/${target.index}`,
    );
    if (capture.value?.id !== id || selected.value?.index !== target.index)
      return;
    comparisonBase.value = previous;
    comparisonDialog.value = true;
  } catch (error) {
    failure(error);
  } finally {
    comparingPrevious.value = false;
  }
}
async function saveView() {
  if (!displayedQuery.value) return;
  try {
    const result = await ElMessageBox.prompt(
      "保存已生效筛选与当前列设置；不包含待提交草稿。",
      "保存常用视图",
      {
        inputValidator: (value) =>
          (!!value?.trim() && value.length <= 40) || "请输入 1～40 字名称",
      },
    );
    const view: SavedView = {
      name: result.value.trim(),
      query: {
        ...displayedQuery.value,
        connection: "",
        range: "",
        messageIndex: "",
      },
      columns: { ...columns.value },
      dictionary: capture.value?.catalog?.sha256 || "",
    };
    const next = [
      view,
      ...savedViews.value.filter((item) => item.name !== view.name),
    ].slice(0, 12);
    if (!writeStored("analyzer.views.v1", next))
      throw new Error("本机存储不可用，视图未保存");
    savedViews.value = next;
    ElMessage.success("视图已保存，不包含连接和区间等记录专属条件");
  } catch (error) {
    if (error !== "cancel" && error !== "close") failure(error);
  }
}
async function applyView(view: SavedView) {
  if (view.dictionary && view.dictionary !== capture.value?.catalog?.sha256) {
    try {
      await ElMessageBox.confirm(
        "此视图来自另一份协议字典，协议 ID 含义可能不同。确认应用？",
        "字典不同",
      );
    } catch {
      return;
    }
  }
  await quickQuery(view.query);
  if (!queryError.value) {
    columns.value = readColumns(JSON.stringify(view.columns));
    viewDialog.value = false;
  }
}
function deleteView(name: string) {
  const next = savedViews.value.filter((item) => item.name !== name);
  if (writeStored("analyzer.views.v1", next)) savedViews.value = next;
}
watch(
  [displayedQuery, selectedRowIndex, displayedPage, tab, detailOpen],
  () => {
    if (!restoringSession) saveReadingPlace();
  },
);
watch(
  () => capture.value?.id,
  () => {
    undoPlace.value = undefined;
    workspaceView.value = "messages";
  },
);

const importGuideDialog = ref(false);
const errorText = ref("");
const autoFollow = ref(true);
const browsingHistory = ref(false);
const viewingTail = ref(false);
const unread = shallowRef<Record<string, number>>({});
const appliedSearch = ref("");
function pauseListForInteraction() {
  autoFollow.value = false;
  pendingLive = false;
  if (refreshTimer) clearTimeout(refreshTimer);
  // 不只禁止下次刷新：在菜单打开之前已经发出的请求也不能替换当前行。
  if (loading.value) cancelQuery("");
}
function toggleBatchMode() {
  if (batchExcluding.value) {
    batchExcluding.value = false;
    selectedProtocols.value = [];
    return;
  }
  pauseListForInteraction();
  rowActions.value?.close();
  batchExcluding.value = true;
}
function toggleProtocolSelection(row: Packet) {
  pauseListForInteraction();
  selectedProtocols.value = toggleProtocolChoices(selectedProtocols.value, row);
}
function excludeSelectedProtocols() {
  if (!selectedProtocols.value.length) return;
  const shown = pendingQuery.value || displayedQuery.value || currentQuery();
  void quickQuery({
    exclude: mergeProtocolExclusions(shown.exclude, selectedProtocols.value),
  });
}
// 只有查询成功发布后才清理已排除的选择；失败时仍可查看选择并重试。
watch(displayedQuery, (query) => {
  if (!query || !selectedProtocols.value.length) return;
  const excluded = new Set(query.exclude.split(/[,，\s]+/).filter(Boolean));
  selectedProtocols.value = selectedProtocols.value.filter(
    (item) => !excluded.has(item.token),
  );
});
watch(
  () => `${capture.value?.id}:${capture.value?.clearVersion || 0}`,
  () => {
    batchExcluding.value = false;
    selectedProtocols.value = [];
  },
);
// 点选详情和实时未读计数不改变列表内容；避免因此重跑整页单元格和操作菜单。
const tableRenderDeps = computed(() => [
  tableRevision.value,
  batchExcluding.value,
  selectedProtocols.value,
  rows.value,
  locatedRow.value,
  busy.value,
  loading.value,
  queryError.value,
  capture.value?.id,
  capture.value?.messageCount,
  activeFilters.value.length,
  detailQuery.value,
  displayedQuery.value?.q,
  ...Object.values(columns.value),
]);
watch([selectedRowIndex, rows], async () => {
  await nextTick();
  packetTable.value?.setCurrentRow(
    rows.value.find((item) => item.index === selectedRowIndex.value),
  );
});
const contextScope = ref<"connection" | "all">("connection");
const contextAnchor = shallowRef<Packet>();
interface SearchOrigin {
  captureId: string;
  search: string;
  appliedSearch: string;
  protocolId: string;
  messageIndex: string;
  appliedProtocolId: string;
  appliedMessageIndex: string;
  include: string;
  exclude: string;
  appliedInclude: string;
  appliedExclude: string;
  direction: string;
  status: string;
  connection: string;
  selectedRange: string;
  hideHeartbeat: boolean;
  order: string;
  page: number;
  scrollTop: number;
  selectedIndex?: number;
  tab: string;
  viewingTail: boolean;
  query: WorkspaceQuery;
}
const searchOrigin = shallowRef<SearchOrigin>();
const detailQuery = computed(
  () => displayedQuery.value?.q || searchOrigin.value?.query.q || "",
);
const fieldMatches = computed(() =>
  findFieldMatches(selected.value?.body, detailQuery.value),
);
const matchPaths = computed(() =>
  fieldMatches.value.matches.map((item) => item.path),
);
const focusedField = ref("");
const hasPrevious = computed(
  () =>
    !!neighbourPage(
      displayedPage.value,
      rows.value.findIndex((item) => item.index === selectedRowIndex.value),
      rows.value.length,
      total.value,
      -1,
      100,
      pageOffset.value,
    ),
);
const hasNext = computed(
  () =>
    !!neighbourPage(
      displayedPage.value,
      rows.value.findIndex((item) => item.index === selectedRowIndex.value),
      rows.value.length,
      total.value,
      1,
      100,
      pageOffset.value,
    ),
);
const activeFilters = computed(() => {
  if (!displayedQuery.value) return [];
  const q = displayedQuery.value;
  const result: { key: string; label: string; value?: string }[] = [];
  if (q.q) result.push({ key: "search", label: `搜索：${q.q}` });
  if (q.protocolId)
    result.push({
      key: "protocolId",
      label: `协议 ID：${q.protocolId}`,
    });
  if (q.messageIndex)
    result.push({
      key: "messageIndex",
      label: `消息序号：${q.messageIndex}`,
    });
  for (const [key, text, prefix] of [
    ["include", q.include, "仅看"],
    ["exclude", q.exclude, "排除"],
  ]) {
    for (const value of text.split(/[,，\s]+/).filter(Boolean))
      result.push({ key, label: `${prefix}：${value}`, value });
  }
  if (q.direction)
    result.push({ key: "direction", label: `方向：${q.direction}` });
  if (q.status)
    result.push({
      key: "status",
      label: `状态：${statusText[q.status] || q.status}`,
    });
  if (q.connection)
    result.push({
      key: "connection",
      label: `连接：${q.connection.slice(0, 8)}`,
    });
  if (q.range)
    result.push({
      key: "range",
      label: `区间：${capture.value?.marks[Number(q.range) - 1]?.name || q.range}`,
    });
  if (q.hideHeartbeat) result.push({ key: "heartbeat", label: "隐藏心跳" });
  return result;
});
const visibleFilters = computed(() =>
  expandedTags.value ? activeFilters.value : activeFilters.value.slice(0, 3),
);
function currentQuery(): WorkspaceQuery {
  return {
    q: appliedSearch.value,
    protocolId: appliedProtocolId.value,
    messageIndex: appliedMessageIndex.value,
    include: appliedInclude.value,
    exclude: appliedExclude.value,
    direction: direction.value,
    status: status.value,
    connection: connection.value,
    hideHeartbeat: hideHeartbeat.value,
    order: order.value,
    range: selectedRange.value,
  };
}
function applyQuery(q: WorkspaceQuery) {
  search.value = appliedSearch.value = q.q;
  protocolId.value = appliedProtocolId.value = q.protocolId;
  messageIndex.value = appliedMessageIndex.value = q.messageIndex;
  include.value = appliedInclude.value = q.include;
  exclude.value = appliedExclude.value = q.exclude;
  direction.value = q.direction;
  status.value = q.status;
  connection.value = q.connection;
  hideHeartbeat.value = q.hideHeartbeat;
  order.value = q.order;
  selectedRange.value = q.range;
}
function cancelQuery(message = "已取消本次查询，保留上次成功结果") {
  messageGate.cancel();
  queryPublication.cancel();
  loading.value = false;
  interactiveLoading.value = false;
  pendingQuery.value = undefined;
  queryError.value = message;
}
async function resetLayout() {
  const top = tableScrollTop();
  columns.value = { ...defaultColumns };
  detailDock.value = "auto";
  sidebarCollapsed.value = true;
  focusMode.value = false;
  expandedTags.value = false;
  for (const direction of ["horizontal", "vertical"])
    localStorage.removeItem(`analyzer.split.${direction}.v3`);
  splitPane.value?.reset();
  tableRevision.value++;
  await nextTick();
  packetTable.value?.setCurrentRow(
    rows.value.find((item) => item.index === selectedRowIndex.value),
  );
  packetTable.value?.setScrollTop(top);
  ElMessage.success("已恢复默认布局，消息、查询条件和选中行已保留");
}
function handleWorkspaceKey(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null;
  const editing =
    !!target?.isContentEditable ||
    !!target?.closest(
      "input,textarea,select,[contenteditable=true],[role=combobox],[role=separator],[role=slider]",
    );
  const modal = Array.from(
    document.querySelectorAll<HTMLElement>(
      ".el-dialog,.el-overlay-message-box,.el-popper",
    ),
  ).some((node) => {
    const r = node.getBoundingClientRect();
    return (
      r.width > 0 &&
      r.height > 0 &&
      getComputedStyle(node).visibility !== "hidden"
    );
  });
  if (
    !allowReadingShortcut(
      event.key,
      editing,
      modal,
      event.altKey || event.ctrlKey || event.metaKey || event.shiftKey,
    )
  )
    return;
  if (event.key === "Escape") {
    if (focusMode.value) focusMode.value = false;
    else if (detailVisible.value) detailOpen.value = false;
    else return;
  } else {
    if (selectedRowIndex.value === undefined || loading.value) return;
    void neighbour(event.key === "ArrowUp" ? -1 : 1);
  }
  event.preventDefault();
}

function removeFilter(key: string, value?: string) {
  if (displayedQuery.value) applyQuery(displayedQuery.value);
  if (key === "search") search.value = "";
  if (key === "protocolId") protocolId.value = "";
  if (key === "messageIndex") messageIndex.value = "";
  if (key === "direction") direction.value = "";
  if (key === "status") status.value = "";
  if (key === "connection") connection.value = "";
  if (key === "range") selectedRange.value = "";
  if (key === "heartbeat") hideHeartbeat.value = false;
  if (key === "include")
    include.value = appliedInclude.value
      .split(/[,，\s]+/)
      .filter((item) => item && item !== value)
      .join(",");
  if (key === "exclude")
    exclude.value = appliedExclude.value
      .split(/[,，\s]+/)
      .filter((item) => item && item !== value)
      .join(",");
  filter();
}
function resetFilters() {
  search.value =
    protocolId.value =
    messageIndex.value =
    include.value =
    exclude.value =
      "";
  direction.value = status.value = connection.value = selectedRange.value = "";
  hideHeartbeat.value = false;
  filter();
}
function tableScrollTop() {
  return (
    (packetTable.value?.$el as HTMLElement | undefined)?.querySelector(
      ".el-table__body-wrapper .el-scrollbar__wrap",
    )?.scrollTop || 0
  );
}
async function revealRow(target: Packet) {
  const id = capture.value?.id;
  locatedRow.value = target.index;
  void select(target);
  await nextTick();
  if (capture.value?.id !== id || selectedRowIndex.value !== target.index)
    return;
  const table = packetTable.value;
  table?.setCurrentRow(target);
  const position = rows.value.findIndex((item) => item.index === target.index);
  const root = table?.$el as HTMLElement | undefined;
  const row = root?.querySelectorAll<HTMLElement>(
    ".el-table__body-wrapper .el-table__row",
  )[position];
  const viewport = root?.querySelector<HTMLElement>(
    ".el-table__body-wrapper .el-scrollbar__wrap",
  );
  if (row && viewport)
    table?.setScrollTop(
      viewport.scrollTop +
        row.getBoundingClientRect().top -
        viewport.getBoundingClientRect().top -
        (viewport.clientHeight - row.offsetHeight) / 2,
    );
}
async function focusField(path: string) {
  focusedField.value = path;
  tab.value = "tree";
  await nextTick();
  const node = Array.from(
    document.querySelectorAll<HTMLElement>(".detail-panel [data-json-path]"),
  ).find((item) => item.dataset.jsonPath === path);
  node?.scrollIntoView({ block: "center", inline: "nearest" });
}
watch([selected, detailQuery], async () => {
  focusedField.value = fieldMatches.value.matches[0]?.path || "";
  if (focusedField.value && tab.value === "tree")
    await focusField(focusedField.value);
});
function setComparisonBase() {
  if (!selected.value) return;
  comparisonBase.value = selected.value;
  ElMessage.success(
    `已固定消息 #${selected.value.index}，选择另一条消息后点击“对比变化”`,
  );
}
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
    const activeId = capture.value?.id;
    if (
      activeId &&
      capture.value?.diskFirstIndex !== undefined &&
      changedIds.includes(activeId) &&
      !autoFollow.value
    ) {
      const metadata = await api<Capture>(`/api/captures/${activeId}/overview`);
      if (capture.value?.id === activeId) capture.value = metadata;
    }
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
interface MessageNavigation {
  position?: number;
  index?: number;
  scrollTop?: number;
  query?: WorkspaceQuery;
  page?: number;
  onCommit?: () => void;
}
const retryAction = shallowRef<() => Promise<void>>();
function retryMessages() {
  void retryAction.value?.();
}
async function loadMessages(
  tail = false,
  background = false,
  navigation?: MessageNavigation,
): Promise<void> {
  if (!capture.value) return;
  const id = capture.value.id;
  const query = {
    ...(navigation?.query || displayedQuery.value || currentQuery()),
  };
  const requestedPage = navigation?.page ?? page.value;
  const request = messageGate.begin();
  const publication = queryPublication.begin();
  loading.value = true;
  interactiveLoading.value = !background;
  pendingQuery.value = query;
  queryError.value = "";
  retryAction.value = async () => {
    if (capture.value?.id === id)
      await loadMessages(tail, false, {
        ...navigation,
        query,
        page: requestedPage,
      });
  };
  const timer = setTimeout(() => {
    if (request.current())
      cancelQuery("查询超过 30 秒，已停止等待；保留上次结果，可重试");
  }, 30000);
  const params = new URLSearchParams({
    q: query.q,
    protocolId: query.protocolId,
    messageIndex: query.messageIndex,
    order: query.order,
    include: query.include,
    exclude: query.exclude,
    direction: query.direction,
    status: query.status,
    connection: query.connection,
    hideHeartbeat: String(query.hideHeartbeat),
    offset: String((requestedPage - 1) * 100),
    limit: "100",
    tail: String(tail),
  });
  if (query.range) {
    const mark = capture.value.marks[Number(query.range) - 1];
    if (mark) {
      params.set("from", String(mark.from));
      params.set("to", String(mark.to));
    }
  }
  try {
    const result = await api<Page>(`/api/captures/${id}/messages?${params}`, {
      signal: request.signal,
    });
    if (
      !Array.isArray(result.items) ||
      result.capture?.id !== id ||
      !Number.isInteger(result.total) ||
      result.total < 0
    )
      throw new Error("查询结果格式异常，旧结果已保留");
    if (
      !request.current() ||
      capture.value?.id !== id ||
      !queryPublication.commit(publication, { query, result })
    )
      return;
    // 条件、计数、页码和数据在同一次同步发布中更新，失败与取消不会发布半套状态。
    navigation?.onCommit?.();
    displayedQuery.value = query;
    rows.value = reconcileRows(rows.value, result.items);
    total.value = result.total;
    pageOffset.value = result.offset ?? (requestedPage - 1) * 100;
    displayedPage.value = Math.floor(pageOffset.value / 100) + 1;
    page.value = displayedPage.value;
    displayedAt.value = new Date().toLocaleTimeString();
    capture.value = result.capture;
    viewingTail.value = tail;
    queryMetrics.value = {
      queryMs: result.queryMs || 0,
      lockWaitMs: result.lockWaitMs || 0,
      snapshotMs: result.snapshotMs || 0,
    };
    if (navigation?.position !== undefined) {
      const target =
        navigation.position < 0
          ? rows.value.at(-1)
          : rows.value[navigation.position];
      if (target) await revealRow(target);
    } else if (
      navigation?.index !== undefined ||
      jumpTarget.value !== undefined
    ) {
      const index = navigation?.index ?? jumpTarget.value;
      const target = result.items.find((item) => item.index === index);
      jumpTarget.value = undefined;
      if (target) await revealRow(target);
      else ElMessage.warning("目标消息已不在当前列表中，可能已被清空或淘汰");
    }
    if (!request.current() || capture.value?.id !== id) return;
    if (navigation?.scrollTop !== undefined) {
      await nextTick();
      if (request.current())
        packetTable.value?.setScrollTop(navigation.scrollTop);
    }
    if (
      selected.value &&
      selected.value.index < (result.capture.firstIndex || 0)
    )
      selectedRowIndex.value = undefined;
    unread.value = { ...unread.value, [id]: 0 };
  } catch (error) {
    if (request.current())
      queryError.value = `本次查询未应用：${error instanceof Error ? error.message : String(error)}。${displayedQuery.value ? "下方保留上次成功结果" : "请重试载入"}`;
  } finally {
    clearTimeout(timer);
    if (request.current()) {
      loading.value = false;
      interactiveLoading.value = false;
      pendingQuery.value = undefined;
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
async function openCapture(item: Capture, restore?: ReadingPlace) {
  if (!item.id) {
    failure(new Error(item.error || "记录不可用"));
    return;
  }
  // 窄窗口的记录栏为浮层，选中后自动收起，避免遮住采集和分析按钮。
  if (window.innerWidth < 1100) sidebarCollapsed.value = true;
  undoPlace.value = undefined;
  messageGate.cancel();
  queryPublication.cancel();
  detailGate.cancel();
  displayedQuery.value = undefined;
  displayedAt.value = "";
  queryError.value = "";
  pendingQuery.value = undefined;
  detailError.value = "";
  detailLoading.value = false;
  detailTarget.value = undefined;
  contextAnchor.value = undefined;
  if (refreshTimer) clearTimeout(refreshTimer);
  pendingLive = false;
  jumpTarget.value = undefined;
  searchOrigin.value = undefined;
  locatedRow.value = undefined;
  comparisonBase.value = undefined;
  comparisonDialog.value = false;
  capture.value = item;
  selected.value = undefined;
  detailOpen.value = false;
  selectedRowIndex.value = undefined;
  rows.value = [];
  browsingHistory.value = false;
  selectedRange.value = "";
  connection.value = "";
  page.value = 1;
  if (restore && validPlace(restore)) {
    applyQuery(restore.query);
    autoFollow.value = false;
    browsingHistory.value = true;
    let restoredPage = restore.page;
    if (restore.selectedIndex !== undefined) {
      const params = new URLSearchParams({
        ...restore.query,
        hideHeartbeat: String(restore.query.hideHeartbeat),
        limit: "1",
      });
      params.delete("range");
      const mark = restore.query.range
        ? item.marks[Number(restore.query.range) - 1]
        : undefined;
      if (mark) {
        params.set("from", String(mark.from));
        params.set("to", String(mark.to));
      }
      if (restore.query.order === "desc")
        params.set(
          "from",
          String(Math.max(mark?.from ?? 0, restore.selectedIndex)),
        );
      else
        params.set(
          "to",
          String(
            Math.min(
              mark?.to ?? Number.MAX_SAFE_INTEGER,
              restore.selectedIndex,
            ),
          ),
        );
      const preceding = await api<Page>(
        `/api/captures/${item.id}/messages?${params}`,
      );
      restoredPage = pageContaining(preceding.total);
    }
    await loadMessages(false, false, {
      query: restore.query,
      page: restoredPage,
    });
    if (capture.value?.id !== item.id || queryError.value) return;
    if (!rows.value.length && total.value > 0) {
      await loadMessages(false, false, {
        page: Math.max(1, Math.ceil(total.value / 100)),
      });
      ElMessage.warning("原页码已超出保留范围，已定位到最近可用页面");
    }
    const target = rows.value.find(
      (row) => row.index === restore.selectedIndex,
    );
    if (target) await select(target);
    else if (restore.selectedIndex !== undefined) {
      ElMessage.warning(
        "原选中消息已不在此页或已淘汰；已恢复筛选与最近可用位置",
      );
      if (rows.value[0]) await select(rows.value[0]);
    }
    detailOpen.value = restore.detailOpen;
    tab.value = ["tree", "json", "hex", "schema", "metadata"].includes(
      restore.tab,
    )
      ? restore.tab
      : "tree";
    await nextTick();
    packetTable.value?.setScrollTop(restore.scrollTop);
  } else {
    await loadMessages(autoFollow.value && item.liveState === "CAPTURING");
  }
}
function filter() {
  undoPlace.value = undefined;
  expandedTags.value = false;
  jumpTarget.value = undefined;
  locatedRow.value = undefined;
  appliedSearch.value = search.value.trim();
  appliedProtocolId.value = protocolId.value.trim();
  appliedMessageIndex.value = messageIndex.value.trim();
  appliedInclude.value = include.value.trim();
  appliedExclude.value = exclude.value.trim();
  localStorage.setItem("analyzer.order", order.value);
  page.value = 1;
  browsingHistory.value = true;
  autoFollow.value = false;
  void loadMessages(false, false, { query: currentQuery(), page: 1 });
}
async function jumpToContext(
  row: Packet,
  scope: "connection" | "all" = contextScope.value,
): Promise<void> {
  if (!capture.value) return;
  const id = capture.value.id;
  const shown = displayedQuery.value || currentQuery();
  const origin: SearchOrigin = {
    captureId: id,
    search: search.value,
    appliedSearch: shown.q,
    protocolId: protocolId.value,
    messageIndex: messageIndex.value,
    appliedProtocolId: shown.protocolId,
    appliedMessageIndex: shown.messageIndex,
    include: include.value,
    exclude: exclude.value,
    appliedInclude: shown.include,
    appliedExclude: shown.exclude,
    direction: shown.direction,
    status: shown.status,
    connection: shown.connection,
    selectedRange: shown.range,
    hideHeartbeat: shown.hideHeartbeat,
    order: shown.order,
    page: displayedPage.value,
    scrollTop: tableScrollTop(),
    selectedIndex: row.index,
    tab: tab.value,
    viewingTail: viewingTail.value,
    query: { ...shown },
  };
  const query = contextQuery(
    shown,
    scope === "connection" ? row.connection : "",
  );
  // 已经处于同一上下文页时直接定位，避免再次查排名、重载列表和丢失滚动位置。
  if (
    Object.entries(query).every(
      ([key, value]) => shown[key as keyof WorkspaceQuery] === value,
    ) &&
    rows.value.some((item) => item.index === row.index)
  ) {
    if (loading.value) cancelQuery("");
    contextAnchor.value = row;
    contextScope.value = scope;
    await revealRow(row);
    return;
  }
  const request = messageGate.begin();
  queryPublication.cancel();
  loading.value = true;
  interactiveLoading.value = true;
  pendingQuery.value = query;
  queryError.value = "";
  browsingHistory.value = true;
  autoFollow.value = false;
  retryAction.value = () => jumpToContext(row, scope);
  const timer = setTimeout(() => {
    if (request.current()) cancelQuery("上下文定位超时，已保留原结果，可重试");
  }, 15000);
  try {
    const preceding = await api<Page>(
      `/api/captures/${id}/messages?${contextRankParams(query, row.index)}`,
      { signal: request.signal },
    );
    if (!request.current() || capture.value?.id !== id) return;
    if (
      preceding.total === 0 ||
      row.index < (preceding.capture.firstIndex || 0)
    ) {
      queryError.value = "目标消息已被清空或淘汰，原搜索结果已保留";
      return;
    }
    await loadMessages(false, false, {
      index: row.index,
      query,
      page: pageContaining(preceding.total),
      onCommit: () => {
        if (!searchOrigin.value) searchOrigin.value = origin;
        contextAnchor.value = row;
        contextScope.value = scope;
        applyQuery(query);
      },
    });
  } catch (error) {
    if (request.current())
      queryError.value = `上下文跳转未应用：${error instanceof Error ? error.message : String(error)}`;
  } finally {
    clearTimeout(timer);
    if (request.current()) {
      loading.value = false;
      interactiveLoading.value = false;
      pendingQuery.value = undefined;
    }
  }
}
async function returnToSearch(): Promise<void> {
  const origin = searchOrigin.value;
  if (!origin || capture.value?.id !== origin.captureId) return;
  autoFollow.value = false;
  browsingHistory.value = true;
  await loadMessages(origin.viewingTail, false, {
    query: origin.query,
    page: origin.page,
    index: origin.selectedIndex,
    scrollTop: origin.scrollTop,
    onCommit: () => {
      applyQuery(origin.query);
      search.value = origin.search;
      protocolId.value = origin.protocolId;
      messageIndex.value = origin.messageIndex;
      include.value = origin.include;
      exclude.value = origin.exclude;
      tab.value = origin.tab;
      searchOrigin.value = undefined;
      contextAnchor.value = undefined;
      jumpTarget.value = undefined;
    },
  });
}
function changePage(value: number) {
  if (value === displayedPage.value && !loading.value) return;
  jumpTarget.value = undefined;
  locatedRow.value = undefined;
  browsingHistory.value = true;
  page.value = value;
  void loadMessages();
}
function browseHistory() {
  browsingHistory.value = true;
  page.value =
    (displayedQuery.value?.order || order.value) === "desc"
      ? 1
      : Math.max(1, Math.ceil(total.value / 100));
  void loadMessages();
}
function jumpLatest() {
  rowActions.value?.close();
  batchExcluding.value = false;
  selectedProtocols.value = [];
  browsingHistory.value = false;
  if (!autoFollow.value) autoFollow.value = true;
  else void loadMessages(true);
}
function captureName(item: Capture) {
  return item.name.startsWith("实时 · ")
    ? "实时 · " + (item.connections[0]?.id.slice(0, 8) || item.id.slice(0, 8))
    : item.name;
}
async function neighbour(delta: number) {
  if (interactiveLoading.value) return;
  const index = rows.value.findIndex(
    (item) => item.index === selectedRowIndex.value,
  );
  const next = neighbourPage(
    displayedPage.value,
    index,
    rows.value.length,
    total.value,
    delta,
    100,
    pageOffset.value,
  );
  if (!next) return;
  browsingHistory.value = true;
  autoFollow.value = false;
  if (index + delta >= 0 && index + delta < rows.value.length) {
    const row = rows.value[index + delta];
    if (row) {
      await revealRow(row);
      return;
    }
  }
  page.value = next.page;
  await loadMessages(false, false, { position: next.position });
}
const navigatingSearchResult = computed(
  () =>
    !!(
      displayedQuery.value?.q ||
      displayedQuery.value?.protocolId ||
      displayedQuery.value?.messageIndex
    ),
);
function openProtocol(row: Packet) {
  // 搜索命中仍可一键跳转上下文；日常浏览直接读详情，不额外查询整页。
  if (navigatingSearchResult.value) void jumpToContext(row);
  else void select(row);
}
async function select(row: Packet | undefined) {
  if (!row || !capture.value) return;
  if (locatedRow.value !== row.index) locatedRow.value = undefined;
  await selectDetail(row);
}
watch(autoFollow, (value) => {
  if (value) {
    rowActions.value?.close();
    batchExcluding.value = false;
    selectedProtocols.value = [];
    browsingHistory.value = false;
  }
  if (
    value &&
    !browsingHistory.value &&
    capture.value?.liveState === "CAPTURING"
  )
    void loadMessages(true);
});
onBeforeUnmount(() => {
  saveReadingPlace();
  window.removeEventListener("beforeunload", saveReadingPlace);
  window.removeEventListener("resize", measureWorkspace);
  window.removeEventListener("keydown", handleWorkspaceKey);
  regionObserver?.disconnect();
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
  const id = capture.value.id;
  busy.value = true;
  try {
    const marked = await post<Capture>(
      `/api/captures/${id}/marks`,
      operation.value,
    );
    if (capture.value?.id === id) {
      capture.value = marked;
      operationStart.value = undefined;
      await quickQuery({ range: String(marked.marks.length) });
    }
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
    context: "",
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
  window.addEventListener("beforeunload", saveReadingPlace);
  window.addEventListener("resize", measureWorkspace);
  window.addEventListener("keydown", handleWorkspaceKey);
  regionObserver = new ResizeObserver(() => measureWorkspace());
  if (dataRegion.value) regionObserver.observe(dataRegion.value);
  measureWorkspace();
  try {
    health.value = await api("/api/health");
    settings.value = await api("/api/settings");
    if (!health.value.catalog.messageCount || settings.value.catalogError) {
      selectedProtocolFile.value = settings.value.protocolFile || "";
      settingsDialog.value = true;
    }
    await reloadCaptures();
    const saved = readStored<ReadingPlace | undefined>(
      "analyzer.reading.v1",
      undefined,
    );
    const previous = validPlace(saved)
      ? captures.value.find((item) => item.id === saved.captureId)
      : undefined;
    const first = previous || captures.value.find((item) => item.id);
    if (first) await openCapture(first, previous ? saved : undefined);
    if (saved && !previous)
      ElMessage.info("上次记录已删除或临时记录已结束，已打开可用记录");
    restoringSession = false;
  } catch (error) {
    failure(error);
  }
});
</script>
<template>
  <div class="workbench" :class="{ 'focus-mode': focusMode }">
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
        <el-dropdown trigger="click"
          ><el-button text>更多</el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item @click="checkUpdate">检查更新</el-dropdown-item
              ><el-dropdown-item @click="importGuideDialog = true"
                >SAZ 导入说明</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
        <!-- 安装包交给浏览器原生下载；fetch 会被同源 CSP 拦截，并额外受跨域读取限制。 -->
        <el-button
          v-if="update.available && update.downloadUrl"
          tag="a"
          text
          :href="update.downloadUrl"
          target="_blank"
          rel="noopener noreferrer"
          title="由浏览器下载；进度请查看浏览器下载列表，失败时可打开发版页面"
          >下载 {{ update.latestVersion }}</el-button
        >
        <el-button
          v-if="update.available && update.releaseUrl"
          tag="a"
          text
          :href="update.releaseUrl"
          target="_blank"
          rel="noopener noreferrer"
          >发版页面</el-button
        >
        <el-switch
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
    <div class="workspace" :class="{ 'sidebar-hidden': !sidebarVisible }">
      <aside v-show="sidebarVisible" class="sidebar">
        <div class="section-heading">
          抓包记录
          <el-button
            text
            size="small"
            @click="sidebarCollapsed = true"
            :disabled="!capture"
            aria-label="收起记录侧栏"
            >收起</el-button
          >
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
          <div class="capture-identity">
            <el-button
              size="small"
              @click="sidebarCollapsed = !sidebarCollapsed"
              :aria-expanded="sidebarVisible"
              >{{ sidebarVisible ? "收起记录" : "记录 / 导入" }}</el-button
            >
            <strong :title="capture?.name">{{
              capture ? captureName(capture) : "选择或导入抓包"
            }}</strong>
            <el-radio-group
              v-model="workspaceView"
              size="small"
              aria-label="数据视图"
            >
              <el-radio-button value="messages">消息</el-radio-button>
              <el-radio-button value="protocols" :disabled="!capture"
                >协议概览</el-radio-button
              >
            </el-radio-group>
            <span
              v-if="capture"
              class="muted retention-state"
              :title="`已清空 ${capture.clearedCount || 0} 条；消息编号不重用`"
            >
              {{ capture.temporary ? "临时" : "已保存" }} · #{{
                capture.firstIndex ?? 0
              }}–{{ capture.lastIndex ?? 0 }}
              <span v-if="capture.evictedCount">
                · 内存淘汰 {{ capture.evictedCount }}</span
              >
              <span v-if="capture.diskFirstIndex !== undefined">
                · 磁盘 #{{ capture.diskFirstIndex }}–{{
                  capture.diskLastIndex
                }}</span
              >
            </span>
            <el-popover trigger="hover" :width="300" placement="bottom-start">
              <template #reference
                ><span class="capture-count"
                  >{{ total.toLocaleString() }} /
                  {{ (capture?.messageCount || 0).toLocaleString() }} 条</span
                ></template
              >
              <p>
                当前列表匹配 {{ total }} 条，记录保留
                {{ capture?.messageCount || 0 }} 条。
              </p>
              <p>
                已解码 {{ capture?.counts.DECODED || 0 }} · 部分
                {{ capture?.counts.PARTIAL || 0 }} · 未映射
                {{ capture?.counts.UNMAPPED || 0 }} · 失败
                {{ capture?.counts.FAILED || 0 }}
              </p>
              <p v-if="capture?.evictedCount">
                已淘汰 {{ capture.evictedCount }} 条旧消息，消息序号保留原值。
              </p>
            </el-popover>
            <el-tag
              v-if="searchOrigin"
              size="small"
              :title="selected?.protocolName"
              >上下文 #{{ locatedRow ?? selectedRowIndex }}</el-tag
            >
            <span
              v-else-if="capture?.liveState === 'CAPTURING'"
              class="reading-hint"
              title="后台采集继续，新消息不会改变当前位置"
              >正在采集 ·
              {{
                autoFollow && !browsingHistory ? "跟随最新" : "阅读历史"
              }}</span
            >
          </div>
          <div class="capture-actions">
            <el-button
              v-if="capture?.liveState === 'CAPTURING' && !capture.continuous"
              size="small"
              :loading="continuousBusy"
              :disabled="!!capture.continuousError"
              @click="enableContinuous"
              >启用持续落盘</el-button
            >
            <span v-if="capture?.continuous" class="muted">持续落盘中</span>
            <el-button
              v-if="capture"
              size="small"
              :loading="snapshotBusy"
              @click="saveCurrentSnapshot"
              >保存当前快照</el-button
            >
            <el-button
              v-if="savedSnapshot"
              text
              size="small"
              @click="openCapture(savedSnapshot)"
              >查看已存快照</el-button
            >
            <el-dropdown v-if="searchOrigin && contextAnchor" trigger="click"
              ><el-button size="small">{{
                contextScope === "connection"
                  ? "当前连接上下文"
                  : "全部连接上下文"
              }}</el-button
              ><template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item
                    @click="
                      contextAnchor &&
                      jumpToContext(contextAnchor, 'connection')
                    "
                    >当前连接上下文</el-dropdown-item
                  ><el-dropdown-item
                    @click="
                      contextAnchor && jumpToContext(contextAnchor, 'all')
                    "
                    >全部连接上下文</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              ></el-dropdown
            >
            <el-button
              v-if="searchOrigin"
              size="small"
              type="primary"
              plain
              @click="returnToSearch"
              >返回搜索结果</el-button
            >
            <el-button
              v-if="
                capture &&
                (capture.temporary || capture.diskFirstIndex !== undefined) &&
                capture.liveState !== 'CAPTURING'
              "
              size="small"
              type="primary"
              @click="saveCapture"
              >保存记录</el-button
            >
            <el-button
              v-if="
                capture?.liveState === 'CAPTURING' &&
                operationStart?.captureId !== capture.id
              "
              size="small"
              @click="beginOperation"
              >开始标记操作</el-button
            >
            <el-button
              v-if="capture && operationStart?.captureId === capture.id"
              size="small"
              type="primary"
              @click="endOperation"
              >结束并选取区间</el-button
            >
            <el-dropdown trigger="click"
              ><el-button size="small" :disabled="!capture">记录操作</el-button
              ><template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item @click="openMark"
                    >标记操作区间</el-dropdown-item
                  ><el-dropdown-item @click="exportCapture"
                    >导出解码 JSON</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              ></el-dropdown
            >
            <el-button
              size="small"
              :disabled="!selected"
              @click="detailOpen = !detailOpen"
              >{{ detailOpen ? "收起详情" : "展开详情" }}</el-button
            >
            <el-button size="small" @click="focusMode = true"
              >专注列表</el-button
            >
          </div>
        </div>
        <div class="workspace-tools">
          <div class="search-toolbar" role="search" aria-label="协议与消息搜索">
            <el-input
              v-model="search"
              clearable
              aria-label="搜索协议或字段"
              placeholder="协议名、字段名或实际值"
              @keyup.enter="filter"
              @clear="filter"
            />
            <el-input
              v-model="protocolId"
              clearable
              aria-label="协议 ID"
              placeholder="协议 ID"
              @keyup.enter="filter"
              @clear="filter"
            />
            <el-input
              v-model="messageIndex"
              clearable
              aria-label="消息序号"
              placeholder="消息序号 #"
              @keyup.enter="filter"
              @clear="filter"
            />
            <el-button
              type="primary"
              :loading="interactiveLoading"
              @click="filter"
              >查询</el-button
            >
          </div>
          <div class="view-toolbar">
            <el-popover
              v-model:visible="advancedFilters"
              trigger="click"
              placement="bottom-start"
              :width="420"
            >
              <template #reference
                ><el-button
                  :type="advancedFilters ? 'primary' : 'default'"
                  :aria-expanded="advancedFilters"
                  >更多筛选</el-button
                ></template
              >
              <div class="advanced-filters filter-popover">
                <el-select
                  v-model="direction"
                  placeholder="全部方向"
                  aria-label="方向筛选"
                  clearable
                  @change="filter"
                  ><el-option label="发送 CS" value="CS" /><el-option
                    label="接收 SC"
                    value="SC"
                /></el-select>
                <el-select
                  v-model="status"
                  placeholder="全部状态"
                  aria-label="解码状态筛选"
                  clearable
                  @change="filter"
                  ><el-option
                    v-for="(label, key) in statusText"
                    :key="key"
                    :label="label"
                    :value="key"
                /></el-select>
                <el-select
                  v-model="connection"
                  placeholder="全部连接"
                  aria-label="连接筛选"
                  clearable
                  @change="filter"
                  ><el-option
                    v-for="item in capture?.connections || []"
                    :key="item.id"
                    :value="item.id"
                    :label="item.id + ' · ' + item.endpoint"
                /></el-select>
                <el-checkbox v-model="hideHeartbeat" @change="filter"
                  >隐藏心跳</el-checkbox
                >
              </div>
              <div v-if="capture?.marks.length" class="marks filter-marks">
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
                ><span class="muted">区间不自动证明请求响应关系。</span>
              </div>
            </el-popover>
            <el-select v-model="order" @change="filter" aria-label="消息排序">
              <el-option label="最新在前 ↓" value="desc" /><el-option
                label="最早在前 ↑"
                value="asc"
              />
            </el-select>
            <el-button @click="filterDialog = true">协议过滤</el-button>
            <el-button @click="viewDialog = true">常用视图</el-button>
            <el-button
              v-if="undoPlace"
              :disabled="interactiveLoading"
              @click="undoQuickQuery"
              >撤销筛选操作</el-button
            >
            <span v-if="hasDraft" class="muted" role="status"
              >输入有待提交条件</span
            >
            <el-button
              :type="batchExcluding ? 'primary' : 'default'"
              :disabled="!capture"
              @click="toggleBatchMode"
            >
              {{ batchExcluding ? "退出批量排除" : "批量排除" }}
            </el-button>
            <el-popover
              v-model:visible="tableSettings"
              trigger="click"
              placement="bottom-end"
              :width="360"
            >
              <template #reference><el-button>表格设置</el-button></template>
              <div class="column-settings">
                <strong>显示列</strong>
                <el-checkbox v-model="columns.time">时间</el-checkbox
                ><el-checkbox v-model="columns.protocolId">协议 ID</el-checkbox>
                <el-checkbox v-model="columns.status">解码状态</el-checkbox
                ><el-checkbox v-model="columns.bytes">字节数</el-checkbox>
                <el-checkbox v-model="columns.sequence">帧序号</el-checkbox
                ><el-checkbox v-model="columns.connection">连接</el-checkbox>
                <el-checkbox v-model="columns.actions">行操作</el-checkbox>
              </div>
              <p class="muted">详情布局，自动模式按数据区实际宽高选择</p>
              <el-radio-group v-model="detailDock" size="small"
                ><el-radio-button value="auto">自动</el-radio-button
                ><el-radio-button value="right">右侧</el-radio-button
                ><el-radio-button value="bottom">底部</el-radio-button
                ><el-radio-button value="overlay"
                  >浮层</el-radio-button
                ></el-radio-group
              >
              <p class="muted">
                空间不足时暂用浮层，保持列表视野；方向键阅读，Esc 收起详情。
              </p>
              <el-button size="small" @click="resetLayout"
                >恢复默认布局</el-button
              >
            </el-popover>
            <el-button
              v-if="capture?.liveState === 'CAPTURING'"
              :type="currentUnread ? 'primary' : 'default'"
              class="jump-latest"
              @click="jumpLatest"
              >{{
                currentUnread
                  ? `${currentUnread} 条新增 · 回到最新`
                  : "回到最新"
              }}</el-button
            >
          </div>
          <div
            v-if="activeFilters.length"
            class="active-filters"
            aria-label="当前生效条件"
          >
            <el-tag
              v-for="item in visibleFilters"
              disable-transitions
              :key="item.key + ':' + (item.value || '')"
              closable
              :title="item.label"
              @close="removeFilter(item.key, item.value)"
              >{{ item.label }}</el-tag
            >

            <el-button
              v-if="activeFilters.length > 3"
              text
              size="small"
              :aria-expanded="expandedTags"
              @click="expandedTags = !expandedTags"
              >{{
                expandedTags
                  ? "收起条件"
                  : `另有 ${activeFilters.length - 3} 条条件`
              }}</el-button
            >
            <el-button
              v-if="activeFilters.length"
              text
              size="small"
              @click="resetFilters"
              >重置全部条件</el-button
            >
          </div>
        </div>
        <div
          v-if="batchExcluding"
          class="batch-exclusion-bar"
          role="region"
          aria-label="批量排除协议"
        >
          <span
            >已选 <b>{{ selectedProtocols.length }}</b> 种协议</span
          >
          <span class="muted"
            >同协议多条消息只选一次；列表已暂停跟随，后台继续采集。</span
          >
          <el-button
            size="small"
            type="primary"
            :disabled="!selectedProtocols.length || interactiveLoading"
            :loading="interactiveLoading"
            @click="excludeSelectedProtocols"
            >排除所选协议</el-button
          >
          <el-button
            size="small"
            :disabled="!selectedProtocols.length"
            @click="selectedProtocols = []"
            >清空选择</el-button
          >
          <el-button size="small" text @click="toggleBatchMode">完成</el-button>
          <div
            v-if="selectedProtocols.length"
            class="batch-protocol-tags"
            aria-label="已选协议"
          >
            <el-tag
              v-for="choice in selectedProtocols"
              :key="choice.key"
              closable
              :title="choice.label"
              @close="
                selectedProtocols = selectedProtocols.filter(
                  (item) => item.key !== choice.key,
                )
              "
              >{{ choice.label }}</el-tag
            >
          </div>
        </div>
        <div v-if="focusMode" class="focus-controlbar">
          <strong>{{ capture ? captureName(capture) : "消息列表" }}</strong>
          <span class="muted"
            >{{ total.toLocaleString() }} 条 ·
            {{
              selectedRowIndex !== undefined
                ? `选中消息 #${selectedRowIndex}`
                : "单击选中，退出专注后查看详情"
            }}</span
          >
          <el-button v-if="searchOrigin" size="small" @click="returnToSearch"
            >返回搜索结果</el-button
          >
          <el-button size="small" type="primary" @click="focusMode = false"
            >退出专注</el-button
          >
        </div>
        <div
          v-if="capture?.continuousError || capture?.recoveryWarning"
          class="error-bar"
          role="alert"
        >
          {{
            capture.continuousError || capture.recoveryWarning
          }}；请核对磁盘与内存保留范围。
        </div>
        <div ref="dataRegion" class="data-region">
          <ProtocolOverview
            v-if="workspaceView === 'protocols' && capture"
            :capture-id="capture.id"
            @only="overviewSelection($event, 'only')"
            @exclude="overviewSelection($event, 'exclude')"
          />
          <SplitPane
            v-show="workspaceView === 'messages'"
            ref="splitPane"
            class="data-split"
            :direction="detailDirection"
            :label="
              detailDirection === 'horizontal'
                ? '调整列表与详情宽度'
                : '调整列表与详情高度'
            "
            :storage-key="`analyzer.split.${detailDirection}.v3`"
            :minimum-primary="
              detailDirection === 'horizontal' ? listWidth : 300
            "
            :minimum-secondary="detailDirection === 'horizontal' ? 320 : 180"
            :initial-ratio="detailDirection === 'horizontal' ? 0.7 : 0.72"
            :secondary-collapsed="!detailVisible"
            :secondary-overlay="effectiveLayout === 'overlay'"
          >
            <template #primary>
              <section :key="tableRevision" class="message-panel">
                <div
                  v-if="loading || queryError"
                  class="query-status"
                  :class="{ 'query-status-error': !!queryError }"
                  role="status"
                >
                  <span
                    :title="loading ? JSON.stringify(pendingQuery) : queryError"
                    >{{
                      loading
                        ? displayedQuery
                          ? "正在查询 · 下方保留上次结果"
                          : "正在载入消息"
                        : queryError
                    }}</span
                  >
                  <el-button
                    v-if="loading"
                    text
                    size="small"
                    @click="cancelQuery()"
                    >取消查询</el-button
                  >
                  <template v-else
                    ><el-button text size="small" @click="retryMessages"
                      >重试查询</el-button
                    ><el-button text size="small" @click="queryError = ''"
                      >关闭提示</el-button
                    ></template
                  >
                </div>
                <el-table
                  v-memo="tableRenderDeps"
                  ref="packetTable"
                  class="packet-table"
                  v-loading="busy"
                  :data="rows"
                  :fit="true"
                  :row-class-name="
                    ({ row }: { row: Packet }) =>
                      row.index === locatedRow ? 'located-row' : ''
                  "
                  height="100%"
                  highlight-current-row
                  empty-text="暂无消息，先导入包含 WebSocket 数据的 SAZ"
                  @row-click="select"
                  row-key="index"
                  size="small"
                >
                  <template #empty
                    ><div class="table-empty">
                      <strong>{{
                        loading
                          ? "正在查询消息"
                          : queryError
                            ? "本次查询未完成"
                            : !capture
                              ? "尚未选择抓包记录"
                              : !capture.messageCount
                                ? "当前记录没有消息"
                                : "没有符合当前条件的消息"
                      }}</strong>
                      <p>
                        {{
                          loading
                            ? "查询期间可以取消；已有结果会保持可读。"
                            : !capture
                              ? "打开“记录 / 导入”选择已有抓包或导入 SAZ。"
                              : !capture.messageCount
                                ? "实时记录请确认采集和游戏连接；历史记录请检查导入文件。"
                                : "检查协议 ID、消息序号、方向、排除条件与心跳设置。"
                        }}
                      </p>
                      <el-button
                        v-if="queryError"
                        size="small"
                        @click="retryMessages"
                        >重新载入</el-button
                      >
                      <el-button
                        v-if="activeFilters.length"
                        size="small"
                        @click="advancedFilters = true"
                        >检查筛选</el-button
                      >
                      <el-button
                        v-if="activeFilters.length"
                        type="primary"
                        size="small"
                        @click="resetFilters"
                        >清除全部筛选</el-button
                      >
                      <el-button
                        v-if="!capture"
                        size="small"
                        @click="sidebarCollapsed = false"
                        >打开记录 / 导入</el-button
                      >
                    </div></template
                  >
                  <el-table-column v-if="batchExcluding" width="36" label="">
                    <template #default="{ row }">
                      <input
                        type="checkbox"
                        class="protocol-select"
                        :aria-label="`选择协议 ${row.protocolName || (row.protocolId ?? '未知')}`"
                        :disabled="!protocolChoice(row)"
                        :checked="
                          selectedProtocols.some(
                            (item) => item.key === protocolChoice(row)?.key,
                          )
                        "
                        @click.stop
                        @change="toggleProtocolSelection(row)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column prop="index" label="消息序号" width="82" />
                  <el-table-column v-if="columns.time" label="时间" width="108"
                    ><template #default="{ row }">{{
                      time(row.time)
                    }}</template></el-table-column
                  >
                  <el-table-column label="方向" width="58"
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
                    width="230"
                    show-overflow-tooltip
                    ><template #default="{ row }"
                      ><button
                        class="protocol-name protocol-button"
                        :title="`${row.protocolName || '未识别消息'} · ${navigatingSearchResult ? '定位上下文并查看详情' : '查看详情'}`"
                        @click.stop="openProtocol(row)"
                      >
                        <HighlightedText
                          :text="row.protocolName || '未识别消息'"
                          :query="detailQuery"
                        /></button></template
                  ></el-table-column>
                  <el-table-column
                    prop="preview"
                    label="消息内容摘要"
                    min-width="220"
                    show-overflow-tooltip
                    ><template #default="{ row }"
                      ><HighlightedText
                        :text="row.preview"
                        :query="displayedQuery?.q" /></template
                  ></el-table-column>
                  <el-table-column
                    v-if="columns.actions"
                    label="操作"
                    width="86"
                  >
                    <template #default="{ row }">
                      <button
                        class="row-exclude"
                        :aria-label="`排除协议 ${row.protocolName || (row.protocolId ?? '未知')}`"
                        :disabled="!protocolChoice(row)"
                        title="排除此协议的全部消息"
                        @click.stop="excludeProtocol(row)"
                      >
                        排除
                      </button>
                      <button
                        class="row-menu"
                        :aria-label="`消息 #${row.index} 操作`"
                        aria-haspopup="dialog"
                        @pointerdown.stop="pauseListForInteraction"
                        @click.stop="rowActions?.open($event, row)"
                      >
                        ⋯
                      </button>
                    </template>
                  </el-table-column>
                  <el-table-column
                    v-if="columns.protocolId"
                    prop="protocolId"
                    label="协议 ID"
                    width="90"
                  />
                  <el-table-column v-if="columns.status" label="状态" width="92"
                    ><template #default="{ row }"
                      ><span
                        class="decode-state"
                        :class="row.status.toLowerCase()"
                        >{{ statusText[row.status] || row.status }}</span
                      ></template
                    ></el-table-column
                  >
                  <el-table-column
                    v-if="columns.sequence"
                    prop="sequence"
                    label="帧序号"
                    width="76"
                  />
                  <el-table-column v-if="columns.bytes" label="字节" width="76"
                    ><template #default="{ row }">{{
                      row.inputFormat === "DECOMPRESSED_MESSAGE"
                        ? row.bytes
                        : row.wireBytes
                    }}</template></el-table-column
                  >
                  <el-table-column
                    v-if="columns.connection"
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
                <MessageRowActions
                  ref="rowActions"
                  :rows="rows"
                  :capture-id="capture?.id"
                  @opening="pauseListForInteraction"
                  @context="jumpToContext"
                  @only="onlyProtocol"
                  @exclude="excludeProtocol"
                />
                <div class="pagination">
                  <span class="muted">{{
                    viewingTail
                      ? `最新 ${rows.length} 条 / 匹配 ${total} 条`
                      : `匹配 ${total} 条 · ${displayedQuery?.order === "desc" ? "最新在前" : "最早在前"} · ${displayedAt || "尚无查询结果"}`
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
                    :current-page="displayedPage"
                    :page-size="100"
                    :total="total"
                    layout="total, prev, pager, next"
                    size="small"
                    @update:current-page="changePage"
                  />
                </div>
              </section>
            </template>
            <template #secondary>
              <section class="detail-panel">
                <div class="detail-heading">
                  <div class="detail-title-row">
                    <el-button
                      text
                      size="small"
                      @click="detailOpen = false"
                      aria-label="收起消息详情"
                      >收起详情</el-button
                    >
                    <b
                      :title="
                        selected?.protocolName || detailTarget?.protocolName
                      "
                      >{{
                        selected?.protocolName ||
                        detailTarget?.protocolName ||
                        "消息详情"
                      }}</b
                    >
                  </div>
                  <span v-if="selected" class="muted detail-meta"
                    >消息 #{{ selected.index }} · 协议 ID
                    {{ selected.protocolId ?? "未知" }} ·
                    {{ selected.direction }} ·
                    {{ selected.bytes }} 字节（解压后）</span
                  ><el-button v-if="selected" text size="small" @click="copy"
                    >复制 JSON</el-button
                  >
                  <el-button
                    v-if="selected"
                    text
                    size="small"
                    @click="neighbour(-1)"
                    :disabled="!hasPrevious || interactiveLoading"
                    >上一条</el-button
                  >
                  <el-button
                    v-if="selected"
                    text
                    size="small"
                    @click="neighbour(1)"
                    :disabled="!hasNext || interactiveLoading"
                    >下一条</el-button
                  >
                  <el-button
                    v-if="selected"
                    text
                    size="small"
                    :loading="comparingPrevious"
                    @click="comparePrevious"
                    >对比上一条同协议</el-button
                  >
                  <span
                    v-if="selected"
                    class="muted"
                    :title="`查询 ${queryMetrics.queryMs.toFixed(1)}ms · 等锁 ${queryMetrics.lockWaitMs.toFixed(1)}ms · 快照 ${queryMetrics.snapshotMs.toFixed(1)}ms`"
                    >详情 {{ detailMs.toFixed(0) }}ms</span
                  >
                  <el-popover
                    v-if="selected"
                    trigger="click"
                    placement="bottom-end"
                    :width="340"
                  >
                    <template #reference
                      ><el-button text size="small"
                        >消息对比{{
                          comparisonBase ? ` · #${comparisonBase.index}` : ""
                        }}</el-button
                      ></template
                    >
                    <div class="detail-actions detail-popover">
                      <span class="muted"
                        >{{
                          searchOrigin
                            ? "完整上下文"
                            : activeFilters.length
                              ? "当前筛选结果"
                              : "全部消息"
                        }}
                        · 上一条 / 下一条支持跨页</span
                      >
                      <el-button size="small" @click="setComparisonBase"
                        >设为对比基准</el-button
                      >
                      <el-button
                        v-if="comparisonBase"
                        size="small"
                        :disabled="
                          comparisonBase.index === selected.index ||
                          selectedRowIndex !== selected.index
                        "
                        @click="comparisonDialog = true"
                        >与 #{{ comparisonBase.index }} 对比变化</el-button
                      >
                      <el-button
                        v-if="comparisonBase"
                        text
                        size="small"
                        @click="comparisonBase = undefined"
                        >清除基准</el-button
                      >
                    </div>
                  </el-popover>
                  <span
                    v-if="!selected && detailTarget"
                    class="muted detail-meta"
                    >目标：消息 #{{ detailTarget.index }} · 协议 ID
                    {{ detailTarget.protocolId ?? "未知" }}</span
                  >
                </div>
                <div v-if="detailLoading" class="detail-state" role="status">
                  正在加载消息 #{{ detailTarget?.index }}，旧消息内容已隐藏。
                </div>
                <div v-else-if="detailError" class="detail-state" role="alert">
                  {{ detailError
                  }}<el-button
                    size="small"
                    @click="detailTarget && select(detailTarget)"
                    >重试详情</el-button
                  >
                </div>
                <template v-if="selected">
                  <p
                    v-if="capture?.operationContext"
                    class="muted operation-context"
                  >
                    操作背景：{{ capture.operationContext }}
                  </p>
                  <details
                    v-if="detailQuery && selected"
                    class="field-matches"
                    :open="fieldMatches.matches.length > 0"
                  >
                    <summary>
                      搜索“{{ detailQuery }}”：消息体命中
                      {{ fieldMatches.matches.length }} 个字段{{
                        fieldMatches.truncated ? "（已达扫描或展示上限）" : ""
                      }}
                    </summary>
                    <div
                      v-if="fieldMatches.matches.length"
                      class="field-match-list"
                    >
                      <button
                        v-for="match in fieldMatches.matches"
                        :key="match.path"
                        :class="{ active: focusedField === match.path }"
                        @click="focusField(match.path)"
                      >
                        {{ match.path || "/" }} = {{ match.value }}
                      </button>
                    </div>
                    <p v-else class="muted">
                      已检查的消息体字段中未找到匹配；搜索也可能命中协议名或协议
                      ID。
                    </p>
                  </details>
                  <div v-if="selected?.error" class="error-bar">
                    {{ selected.error }}
                  </div>
                  <details
                    v-if="selected?.warnings?.length"
                    class="warning-bar compact-warning"
                  >
                    <summary>
                      解码提示（{{ selected.warnings.length }}）
                    </summary>
                    <div>{{ selected.warnings.join("；") }}</div>
                  </details>
                  <el-tabs v-model="tab" class="detail-tabs">
                    <el-tab-pane label="JSON 树" name="tree"
                      ><div v-if="tab === 'tree'" class="detail-content">
                        <JsonTree
                          v-if="selected?.body !== undefined"
                          :key="selected.index"
                          :value="selected.body"
                          :match-paths="matchPaths"
                          :focused-path="focusedField"
                        />
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
                      <div v-if="tab === 'json'" class="detail-content">
                        <TextPreview :value="selected.body" />
                      </div>
                    </el-tab-pane>
                    <el-tab-pane label="原始包体" name="hex"
                      ><div v-if="tab === 'hex'" class="detail-content">
                        <p class="muted">
                          解压后的完整业务包（消息头 + Thrift Compact
                          消息体；头长见元数据 headerBytes）
                        </p>
                        <TextPreview :value="selected.packetHex" format="hex" />
                        <p class="muted">
                          {{
                            selected?.inputFormat === "DECOMPRESSED_MESSAGE"
                              ? "实时采集器已解压并重组消息，没有提供原始 wire 帧；上方保留的是完整业务包。"
                              : "SAZ 中的原始 WebSocket 帧（包含帧头、掩码与压缩字节）"
                          }}
                        </p>
                        <TextPreview
                          :value="selected.wireHex"
                          format="hex"
                        /></div
                    ></el-tab-pane>
                    <el-tab-pane label="字段定义" name="schema"
                      ><div v-if="tab === 'schema'" class="detail-content">
                        <p class="muted">
                          来源：{{ selected?.catalog?.sourceFile
                          }}<span v-if="selected?.definition?.line"
                            >:{{ selected.definition.line }}</span
                          >
                          · 字典 SHA256
                          {{ selected?.catalog?.sha256.slice(0, 16) }}
                        </p>
                        <p class="muted">
                          字段定义格式：[字段名, Thrift 类型]；2 BOOL · 8 I32 ·
                          10 I64 · 11 STRING · [12,结构名] STRUCT ·
                          [15,元素类型] LIST · [13,键类型,值类型] MAP
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
                </template>
              </section>
            </template>
          </SplitPane>
        </div>
      </main>
    </div>
    <el-dialog
      v-model="viewDialog"
      title="常用分析视图"
      width="min(600px,94vw)"
    >
      <p class="muted">
        保存已生效筛选和显示列；不保存消息、连接、区间或未提交输入。最多 12
        个，同名覆盖。
      </p>
      <el-button :disabled="!displayedQuery" @click="saveView"
        >保存当前视图</el-button
      >
      <p v-if="!savedViews.length" class="muted">尚未保存视图</p>
      <div v-for="view in savedViews" :key="view.name" class="saved-view-row">
        <strong>{{ view.name }}</strong
        ><span class="muted"
          >字典 {{ view.dictionary?.slice(0, 12) || "未知" }}</span
        >
        <el-button :disabled="!capture" @click="applyView(view)">应用</el-button
        ><el-button text @click="deleteView(view.name)">删除</el-button>
      </div>
    </el-dialog>
    <el-dialog
      v-model="comparisonDialog"
      title="消息字段变化对比"
      width="min(1000px, 95vw)"
      destroy-on-close
    >
      <MessageComparison
        v-if="comparisonBase && selected"
        :before="comparisonBase"
        :after="selected"
      />
    </el-dialog>
    <el-dialog
      v-model="markDialog"
      title="标记区间 / 保存 AI 分析快照"
      width="500px"
      ><p class="muted">
        以列表“消息序号”列为边界，保存一次操作前后的消息范围。区间不自动证明请求与推送的因果关系。“保存分析快照”会复制并保存该区间和字典，原连接继续采集；已淘汰的消息无法补回。
      </p>
      <el-form label-width="90px"
        ><el-form-item label="操作名称"
          ><el-input
            v-model="operation.name"
            maxlength="100"
            placeholder="例如：联盟科技捐献一次" /></el-form-item
        ><el-form-item label="操作背景"
          ><el-input
            v-model="operation.context"
            type="textarea"
            :rows="2"
            maxlength="2000"
            placeholder="操作前状态、执行动作和想确认的问题（随快照保存）" /></el-form-item
        ><el-form-item label="起始序号 #"
          ><el-input-number
            v-model="operation.from"
            :min="capture?.firstIndex || 0"
            :max="
              capture?.lastIndex ??
              Math.max(0, (capture?.messageCount || 1) - 1)
            " /></el-form-item
        ><el-form-item label="结束序号 #"
          ><el-input-number
            v-model="operation.to"
            :min="operation.from"
            :max="
              capture?.lastIndex ??
              Math.max(0, (capture?.messageCount || 1) - 1)
            " /></el-form-item></el-form
      ><template #footer
        ><el-button @click="markDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="freezeOperation"
          >保存快照并分析</el-button
        ><el-button type="primary" :loading="busy" @click="mark"
          >保存区间</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="filterDialog" title="协议过滤" width="540px">
      <p class="muted">
        协议名或协议 ID 普通词按模糊包含匹配；id:12 精确匹配协议
        ID，name:GcUpdate
        精确匹配协议名。支持逗号分隔，同组任一匹配、排除优先。行内与批量快捷操作自动使用精确条件。
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
    <el-dialog
      v-model="importGuideDialog"
      title="SAZ 导入说明"
      width="min(600px, 94vw)"
    >
      <p class="muted">
        用于分析已有抓包文件。实时采集请使用顶部的“接入步骤”。
      </p>
      <ol class="guide">
        <li>
          准备 .saz 文件，需包含从握手开始的完整主游戏 WebSocket
          会话。已有文件可直接导入，无需为导入开启系统代理。
        </li>
        <li>在“设置 / 协议加载”中加载与抓包版本匹配的客户端协议字典。</li>
        <li>
          打开“记录 / 导入”，点击“选择 SAZ
          文件”；也可填写当前运行工作台的这台电脑上的文件绝对路径，再点击“从路径导入”。
        </li>
        <li>
          导入完成后选择该记录，按协议名、协议 ID
          或消息序号查找，查看消息字段与原始包体。
        </li>
      </ol>
      <p class="muted">
        Windows、macOS、Linux
        均使用同一导入入口。旧记录保留导入时的字典；要使用新字典重新解析，请重新导入原文件。
      </p>
    </el-dialog>
  </div>
</template>
