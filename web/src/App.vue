<script setup lang="ts">
import AnalysisTabsBar from "./AnalysisTabsBar.vue";
import { useCaptureFeed } from "./useCaptureFeed";
import { useSavedViews } from "./useSavedViews";
import SavedViewsDialog from "./SavedViewsDialog.vue";
import WorkbenchHeader from "./WorkbenchHeader.vue";
import CaptureRecordToolbar from "./CaptureRecordToolbar.vue";
import { useWorkbenchLayout } from "./useWorkbenchLayout";
import { useMessageRelations } from "./useMessageRelations";
import { useMessageHistory } from "./useMessageHistory";
import { useMessageLoader } from "./useMessageLoader";
import { useCaptureOperations } from "./useCaptureOperations";
import CaptureOperationDialog from "./CaptureOperationDialog.vue";
import CaptureMessageTable from "./CaptureMessageTable.vue";
import CaptureSidebar from "./CaptureSidebar.vue";
import { captureName } from "./capturePresentation";
import { useWorkbenchSettings } from "./useWorkbenchSettings";
import WorkbenchSettingsDialog from "./WorkbenchSettingsDialog.vue";
import { DEFAULT_PAGE_SIZE, MAX_CAPTURE_NAME_LENGTH } from "./constants";

import {
  computed,
  defineAsyncComponent,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

import CaptureFilterToolbar from "./CaptureFilterToolbar.vue";
import { useWorkspaceFilters } from "./useWorkspaceFilters";
import { statusText } from "./capturePresentation";

import MessageDetailPanel from "./MessageDetailPanel.vue";
import { useMessageInspector } from "./useMessageInspector";
import MessageGroupPreview from "./MessageGroupPreview.vue";
import { useReadingTools } from "./useReadingTools";
import { useAnalysisTabs, type AnalysisTab } from "./useAnalysisTabs";
import { useAnalysisProject } from "./useAnalysisProject";
import { useResearchWorkspace } from "./useResearchWorkspace";
import MessageNoteDialog from "./MessageNoteDialog.vue";
import { useFieldWatch } from "./useFieldWatch";
import {
  foldRows,
  groupedMessageRows,
  type MessageGrouping,
  repeatGroups,
} from "./analysisTools";
const ResearchPanel = defineAsyncComponent(() => import("./ResearchPanel.vue"));
import SplitPane from "./SplitPane.vue";
import LiveCapturePanel from "./LiveCapturePanel.vue";
import ConnectionIdentityPanel from "./ConnectionIdentityPanel.vue";

import MessageComparison from "./MessageComparison.vue";

import ProtocolOverview from "./ProtocolOverview.vue";
import LiveAttentionBar from "./LiveAttentionBar.vue";

import { useCaptureActivity } from "./useCaptureActivity";
import { useActivityWindow } from "./useActivityWindow";
import { useProtocolWatch } from "./useProtocolWatch";

import {
  activityQuery,
  readingRange,
  relaxFilter,
  type EmptyReason,
} from "./liveReading";

import ProtocolQueryPanel from "./ProtocolQueryPanel.vue";
import {
  protocolMessageQuery,
  type ProtocolQueryTarget,
} from "./protocolQuery";

import { useQueryResults } from "./useQueryResults";
import { useMessageSelection } from "./useMessageSelection";
import {
  readStored,
  writeStored,
  validQuery,
  validPlace,
  patchQuery,
  type ReadingPlace,
} from "./workspaceSession";
import {
  protocolChoice,
  mergeProtocolExclusions,
  toggleProtocolChoices,
  type ProtocolChoice,
} from "./protocolSelection";
import {
  allowReadingShortcut,
  type WorkspaceQuery,
} from "./workspaceInteractions";
import { pageContaining } from "./analysisWorkspace";
import { api, post, type Capture, type Packet, type Page } from "./types";

const livePanel = ref<InstanceType<typeof LiveCapturePanel>>();
const messageTable = ref<InstanceType<typeof CaptureMessageTable>>();
const packetTable = computed(() => messageTable.value?.table);
const searchHistory = ref<InstanceType<typeof CaptureFilterToolbar>>();

const rowActions = computed(() => messageTable.value?.rowActions);
const batchExcluding = ref(false);
const selectedProtocols = shallowRef<ProtocolChoice[]>([]);
const captures = shallowRef<Capture[]>([]);
const connectionIdentityDialog = ref(false);
function identityChanged(updated: Capture) {
  captures.value = captures.value.map((item) =>
    item.id === updated.id ? updated : item,
  );
  if (capture.value?.id === updated.id) capture.value = updated;
}
const capture = shallowRef<Capture>();
const readingTools = useReadingTools({
  id: () => capture.value?.id,
  table: () => packetTable.value,
  place: readingPlace,
  following: () => isFollowing.value,
  field: () => focusedField.value,
  restore: (checkpoint) =>
    restoreAnalysisTab({
      id: -1,
      name: "返回刚才位置",
      place: checkpoint.place,
      following: checkpoint.following,
      field: checkpoint.field,
    }),
  select: (row) => select(row),
  inspect: (row) => selectDetail(row),
  pause: () => pauseListForInteraction("history"),
  rebuild: () => tableRevision.value++,
});
const {
  layout: workspaceLayout,
  navigationReturn,
  restoring: restoringNavigation,
  selectedRange: selectedOperationRange,
  previewGroup,
  previewOpen: groupPreviewOpen,
  checkpoint: saveNavigationCheckpoint,
  back: returnReadingCheckpoint,
  maximize: toggleDetailMaximize,
  rowClick: tableRowClick,
  preview: openGroupPreview,
  closePreview: closeGroupPreview,
  inspect: inspectGroupMessage,
  resetColumn: resetColumnFromHeader,
} = readingTools;
const researchWorkspace = useResearchWorkspace({
  capture: () => capture.value,
  selected: () => selected.value,
  canEdit: () => !health.value.analysisProjects || projectSync.ready.value,
  refreshMessages: () => loadMessages(viewingTail.value),
  addWatch: (field, packet) => fieldWatch.add(field, packet),
  failure,
});
const {
  research,
  researchOpen,
  researchMode,
  openResearch,
  watchField,
  addFieldColumn,
  removeFieldColumn,
  removeNote,
  editNote,
  fieldAnnotations,
} = researchWorkspace;

const messageGrouping = ref<MessageGrouping>("none");
const foldRepeats = computed(() => messageGrouping.value !== "none");
const expandedRepeats = ref(new Set<number>());
const temporaryView = shallowRef<{
  name: string;
  origin: WorkspaceQuery;
  applied: WorkspaceQuery;
}>();
const analysisTabState = useAnalysisTabs({
  id: () => capture.value?.id,
  place: readingPlace,
  follow: () => autoFollow.value,
  field: () => focusedField.value,
  temporary: () => temporaryView.value,
  restore: restoreAnalysisTab,
});
const {
  tabs: analysisTabs,
  active: activeAnalysisTab,
  switching: switchingAnalysisTab,
} = analysisTabState;
const queryResults = useQueryResults();
const {
  rows,
  total,
  page,
  displayedPage,
  displayedQuery,
  pendingQuery,
  queryError,
  displayedAt,
  queryPublication,
  messageGate,
  queryMetrics,
} = queryResults;
const selection = useMessageSelection(
  () => capture.value,
  () => pauseListForInteraction("selection"),
  () => pauseOnSelect.value,
);

const {
  selected,
  selectedRowIndex,
  detailLoading,
  detailError,
  detailTarget,
  detailOpen,
  detailGate,
  detailCache,
  selectDetail,
} = selection;
const tab = ref("tree");
const filters = useWorkspaceFilters({
  capture: () => capture.value,
  query: () => displayedQuery.value,
});
const {
  search,
  protocolId,
  messageIndex,
  appliedProtocolId,
  appliedMessageIndex,
  include,
  exclude,
  appliedInclude,
  appliedExclude,
  direction,
  status,
  connection,
  hideHeartbeat,
  selectedRange,
  appliedSearch,
  order,
  expandedTags,
  advancedFilters,
  draftQuery,
  activeFilters,
  currentQuery,
  applyQuery,
} = filters;
const workbenchLayout = useWorkbenchLayout({
  detailOpen,
  hasCapture: () => !!capture.value,
  fieldCount: () => research.columns.value.length,
  batchExcluding: () => batchExcluding.value,
  table: () => packetTable.value,
  scrollTop: tableScrollTop,
  selectedRow: () =>
    rows.value.find((item) => item.index === selectedRowIndex.value),
  resetFilterPresentation: () => {
    expandedTags.value = false;
    workspaceLayout.reset();
    workspaceLayout.maximized.value = false;
  },
});
const {
  columns,
  tableRevision,
  splitPane,
  dataRegion,
  sidebarCollapsed,
  focusMode,
  detailDock,
  listWidth,
  effectiveLayout,
  detailDirection,
  sidebarVisible,
  detailVisible,
  resetLayout,
} = workbenchLayout;

watch(detailVisible, (visible) => {
  if (!visible) workspaceLayout.maximized.value = false;
});
const busy = ref(false);
const health = ref({
  ready: false,
  apiVersion: 0,
  analysisTools: false,
  analysisProjects: false,
  activityWindow: false,
  catalog: {
    messageCount: 0,
    definitionCount: 0,
    sha256: "",
    sourceFile: "",
    sourceRoot: "",
  },
});
const supportsAnalysis = computed(
  () => health.value.apiVersion === 2 && health.value.analysisTools,
);
const locatedRow = ref<number>();
const workbenchSettings = useWorkbenchSettings({
  catalogLoaded: (catalog) => (health.value.catalog = catalog),
  failure,
});
const { settingsDialog, update, settings, selectedProtocolFile, openSettings } =
  workbenchSettings;
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
      messageLoader.clearDeferred();
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
    messageLoader.clearDeferred();
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
          (!!value?.trim() && value.length <= MAX_CAPTURE_NAME_LENGTH) ||
          "请输入 1～100 字名称",
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
    emptyReasons.value = [];
    laterMessages.value = undefined;
    laterError.value = "";
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

const dark = ref(localStorage.getItem("analyzer.dark") === "true");
const captureOperations = useCaptureOperations({
  capture,
  busy,
  selected: () => selected.value,
  projectEnabled: () => health.value.analysisProjects,
  supportsAnalysis: () => supportsAnalysis.value,
  flushProject: () => projectSync.flush(),
  reloadCaptures,
  openCapture,
  applyQuery,
  quickQuery,
  openResearch,
  failure,
});
const { operation, markDialog, operationStart, lastOperationRange, mark } =
  captureOperations;

const mainWorkspace = ref<"captures" | "catalog">("captures");
const protocolQueryTarget = shallowRef<ProtocolQueryTarget>();
let protocolQuerySequence = 0;
function returnCaptureWorkspace() {
  mainWorkspace.value = "captures";
  if (pauseReason.value === "catalog")
    pauseReason.value = selected.value ? "selection" : "manual";
}
function openProtocolQuery(fromMessage = false) {
  pauseListForInteraction();
  if (
    fromMessage &&
    selected.value?.protocolId !== undefined &&
    capture.value
  ) {
    protocolQueryTarget.value = {
      key: String(selected.value.protocolId),
      captureId: capture.value.id,
      fieldPath: focusedField.value || "",
      sequence: ++protocolQuerySequence,
    };
  } else if (!protocolQueryTarget.value) {
    protocolQueryTarget.value = {
      captureId: capture.value?.id || "",
      sequence: ++protocolQuerySequence,
    };
  }
  mainWorkspace.value = "catalog";
}
async function showProtocolMessages(value: {
  captureId: string;
  protocolId: number;
}) {
  if (capture.value?.id !== value.captureId) {
    ElMessage.warning("当前记录已变化，请重新选择记录字典后查看消息");
    return;
  }
  await quickQuery(protocolMessageQuery(value.protocolId));
  if (!queryError.value && capture.value?.id === value.captureId)
    mainWorkspace.value = "captures";
}
const workspaceView = ref<"messages" | "protocols">("messages");
watch(workspaceView, (view) => {
  if (view === "protocols") pauseListForInteraction();
});
const undoPlace = shallowRef<ReadingPlace>();
const savedViewState = useSavedViews({
  capture: () => capture.value,
  query: () => displayedQuery.value,
  columns,
  quickQuery,
  queryError,
  failure,
});
const { viewDialog } = savedViewState;

let restoringSession = true;

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
  if (place) {
    writeStored("analyzer.reading.v1", place);
    analysisTabState.remember();
  }
}
function useSelectedRange(action: "mark" | "snapshot" | "review") {
  const range = selectedOperationRange.value;
  if (!range) return;
  operation.value = { name: "", context: "", ...range };
  lastOperationRange.value = { ...range };
  if (action === "review") openResearch("review");
  else {
    operation.value.name = action === "snapshot" ? "区间分析快照" : "";
    markDialog.value = true;
  }
}
async function confirmReloadProject() {
  try {
    await ElMessageBox.confirm(
      "重新加载会用记录中已保存的成果替换当前未保存的批注与视图，请先导出需要保留的输入。",
      "重新加载分析成果",
      {
        type: "warning",
        confirmButtonText: "重新加载",
        cancelButtonText: "保留当前输入",
      },
    );
    await projectSync.reload();
  } catch {}
}
watch(
  () => capture.value?.id,
  () => {
    workspaceLayout.maximized.value = false;
    navigationReturn.value = undefined;
    selectedOperationRange.value = undefined;
    expandedRepeats.value = new Set();
    temporaryView.value = undefined;
    lastOperationRange.value = undefined;
  },
);
async function locateWatchedField(key: string) {
  const item = fieldWatch.items.value.find((item) => item.key === key);
  if (!item || !capture.value) return;
  await locateResearch({
    captureId: capture.value.id,
    index: item.index,
    path: item.path,
  });
  item.changed = false;
}
async function checkBackend() {
  try {
    health.value = await api("/api/health");
  } catch (error) {
    failure(error);
  }
}
const groupingRows = computed(() =>
  groupedMessageRows(rows.value, messageGrouping.value),
);
const groupedRepeats = computed(() => repeatGroups(groupingRows.value));
const visibleRows = computed(() =>
  foldRows(groupingRows.value, foldRepeats.value, expandedRepeats.value),
);
function toggleRepeat(index: number) {
  const next = new Set(expandedRepeats.value);
  if (next.has(index)) next.delete(index);
  else next.add(index);
  expandedRepeats.value = next;
}
async function toggleFold() {
  expandedRepeats.value = new Set();
  await loadMessages(viewingTail.value);
}
async function locateResearch(value: {
  captureId: string;
  index: number;
  path?: string;
}) {
  researchOpen.value = false;
  try {
    if (value.captureId !== capture.value?.id) {
      const record = captures.value.find((item) => item.id === value.captureId);
      if (!record) return;
      await openCapture(record);
    }
    const id = capture.value?.id;
    if (!id || id !== value.captureId) return;
    const packet = await api<Packet>(
      `/api/captures/${id}/messages/${value.index}`,
    );
    if (capture.value?.id !== id) return;
    await jumpToContext(packet, "all");
    if (capture.value?.id === id && value.path) await focusField(value.path);
  } catch (error) {
    ElMessage.warning(
      `无法定位原消息，批注仍保留：${error instanceof Error ? error.message : String(error)}`,
    );
  }
}
async function showResearchRange(value: {
  from: number;
  to: number;
  index?: number;
  name: string;
}) {
  saveNavigationCheckpoint();
  const origin =
    temporaryView.value?.origin || displayedQuery.value || currentQuery();
  await quickQuery({ range: `recent:${value.from}:${value.to}` });
  if (queryError.value) return;
  temporaryView.value = {
    name: value.name,
    origin,
    applied: displayedQuery.value!,
  };
  researchOpen.value = false;
  const row = rows.value.find((item) => item.index === value.index);
  if (row) await revealRow(row);
}
function newAnalysisTab() {
  try {
    analysisTabState.add();
  } catch (error) {
    failure(error);
  }
}
const switchAnalysisTab = analysisTabState.select;
const closeAnalysisTab = analysisTabState.close;
async function renameAnalysisTab(id: number) {
  const item = analysisTabs.value.find((item) => item.id === id);
  if (!item) return;
  try {
    const result = await ElMessageBox.prompt(
      "页签名称（本记录内）",
      "重命名分析页签",
      {
        inputValue: item.name,
        inputValidator: (value) =>
          (!!value?.trim() && value.length <= 20) || "请输入 1～20 字名称",
      },
    );
    analysisTabState.rename(id, result.value);
  } catch {}
}
async function restoreAnalysisTab(target: AnalysisTab): Promise<boolean> {
  const place = target.place,
    id = capture.value?.id;
  if (!place || place.captureId !== id) return false;
  pauseListForInteraction("history");
  detailGate.cancel();
  detailLoading.value = false;
  browsingHistory.value = !target.following;
  await loadMessages(target.following, false, {
    query: place.query,
    page: place.page,
    scrollTop: place.scrollTop,
    onCommit: () => {
      applyQuery(place.query);
      selected.value = undefined;
      selectedRowIndex.value = undefined;
      detailOpen.value = false;
      temporaryView.value = target.temporary;
    },
  });
  if (queryError.value || capture.value?.id !== id) return false;
  if (place.selectedIndex !== undefined) {
    try {
      const row =
        rows.value.find((item) => item.index === place.selectedIndex) ||
        (await api<Packet>(
          `/api/captures/${id}/messages/${place.selectedIndex}`,
        ));
      if (capture.value?.id !== id) return false;
      await selectDetail(row);
    } catch {
      ElMessage.info("原选中消息已不在保留范围，已恢复筛选与位置");
    }
    detailOpen.value = place.detailOpen;
    tab.value = place.tab;
    if (target.field && selected.value) await focusField(target.field);
  }
  browsingHistory.value = !target.following;
  autoFollow.value = target.following;
  await nextTick();
  packetTable.value?.setScrollTop(place.scrollTop);
  return true;
}
/** 保留输入草稿；只在查询成功后发布快捷操作及撤销位置。 */
async function quickQuery(patch: Partial<WorkspaceQuery>) {
  if (!capture.value) return;
  const before = displayedQuery.value || currentQuery();
  const base = pendingQuery.value || before;
  const draft = draftQuery();
  const previous = readingPlace();
  const next = patchQuery(base, patch);
  pauseListForInteraction("filter");
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
      actionFeedback.value = "";
      if (patch.exclude !== undefined && patch.exclude !== before.exclude) {
        const old = new Set(before.exclude.split(/[,，\s]+/).filter(Boolean));
        const added = patch.exclude
          .split(/[,，\s]+/)
          .filter((token) => token && !old.has(token));
        if (added.length) showActionFeedback(`已排除 ${added.length} 种协议`);
      }
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
      actionFeedback.value = "";
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

const errorText = ref("");
const autoFollow = ref(true);
const pauseOnSelect = ref(
  readStored<unknown>("analyzer.pause-on-select.v1", true) !== false,
);
watch(pauseOnSelect, (value) =>
  writeStored("analyzer.pause-on-select.v1", value),
);
const pauseReason = ref("manual");
const activity = useCaptureActivity(() => capture.value);
const fieldWatch = useFieldWatch(
  () => capture.value,
  () => activity.latest.value?.lastIndex,
  () => supportsAnalysis.value,
);
const protocolWatch = useProtocolWatch(
  () => capture.value,
  () => activity.latest.value,
);
const isFollowing = computed(() => autoFollow.value && !browsingHistory.value);
const rawActivity = computed(() =>
  isFollowing.value ? activity.recent.value : activity.unread.value,
);
const displayedActivity = computed(() => activityWindow.value.value);
const pauseDescription = computed(() => {
  if (pauseReason.value === "selection" && selected.value)
    return `正在查看消息 #${selected.value.index}`;
  return (
    (
      {
        history: "正在翻阅历史",
        filter: "正在查看筛选结果",
        menu: "正在操作消息",
        locate: "正在定位消息",
        catalog: "正在查看协议定义",
        manual: "手动暂停",
      } as Record<string, string>
    )[pauseReason.value] || "正在阅读"
  );
});
const actionFeedback = ref("");
let actionFeedbackTimer: ReturnType<typeof setTimeout> | undefined;
function showActionFeedback(message: string) {
  actionFeedback.value = message;
  if (actionFeedbackTimer) clearTimeout(actionFeedbackTimer);
  actionFeedbackTimer = setTimeout(() => (actionFeedback.value = ""), 8000);
}
function setListFollow(value: boolean) {
  if (!value) pauseListForInteraction("manual");
  else autoFollow.value = true;
}
function pauseHistory() {
  pauseListForInteraction("history");
  browsingHistory.value = true;
}
function toggleWatch(row: Packet) {
  const message = protocolWatch.toggle(row);
  if (message) ElMessage.warning(message);
}
async function viewWatched(token: string) {
  const id = capture.value?.id;
  const count = protocolWatch.count(token);
  await quickQuery({
    include: token,
    protocolId: "",
    messageIndex: "",
    q: "",
    exclude: "",
    range: "",
  });
  if (!queryError.value && capture.value?.id === id)
    protocolWatch.acknowledge(token, count);
}
async function viewActivity(kind: "all" | "first" | "attention") {
  const delta = displayedActivity.value;
  const snapshot = activityWindow.source.value;
  if (!delta || !delta.total || (kind === "first" && !delta.firstSeen.length))
    return;
  const id = capture.value?.id;
  const origin =
    temporaryView.value?.origin || displayedQuery.value || currentQuery();
  await quickQuery(activityQuery(delta, kind));
  if (!queryError.value && capture.value?.id === id) {
    temporaryView.value = {
      name:
        kind === "all"
          ? "查看新增消息"
          : kind === "first"
            ? "查看首次出现协议"
            : "查看需关注消息",
      origin,
      applied: displayedQuery.value!,
    };
    activity.acknowledge(snapshot);
  }
}
async function removeBlockingFilter(reason: EmptyReason) {
  await quickQuery(relaxFilter(displayedQuery.value || currentQuery(), reason));
}
const highlightedRow = ref<number>();
let rowHighlightTimer: ReturnType<typeof setTimeout> | undefined;

function highlightRow(index: number) {
  highlightedRow.value = index;
  if (rowHighlightTimer) clearTimeout(rowHighlightTimer);
  rowHighlightTimer = setTimeout(
    () => (highlightedRow.value = undefined),
    2200,
  );
}
const browsingHistory = ref(false);
const viewingTail = ref(false);
const unread = shallowRef<Record<string, number>>({});
function pauseListForInteraction(reason = "menu") {
  pauseReason.value = reason;
  autoFollow.value = false;
  messageLoader.clearDeferred();
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
  if (!query?.range.startsWith("recent:")) temporaryView.value = undefined;
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
  workspaceLayout.widths.value,
  selectedOperationRange.value,
  foldRepeats.value,
  messageGrouping.value,
  expandedRepeats.value,
  research.columns.value,
  tableRevision.value,
  protocolWatch.choices.value,
  highlightedRow.value,
  recentRows.value,
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
watch([selectedRowIndex, groupingRows], async () => {
  const chosen = groupingRows.value.find(
    (item) => item.index === selectedRowIndex.value,
  );
  if (
    !readingTools.inspecting.value &&
    chosen?.repeatOf !== undefined &&
    !expandedRepeats.value.has(chosen.repeatOf)
  ) {
    const next = new Set(expandedRepeats.value);
    next.add(chosen.repeatOf);
    expandedRepeats.value = next;
  }
  await nextTick();
  packetTable.value?.setCurrentRow(
    groupingRows.value.find((item) => item.index === selectedRowIndex.value),
  );
});
const activityWindow = useActivityWindow(
  () => capture.value,
  () => rawActivity.value,
  () => displayedQuery.value || currentQuery(),
  () => activity.latest.value,
  () => !!health.value.activityWindow,
  () =>
    isFollowing.value
      ? "following"
      : `unread:${activity.baseline.value?.lastIndex ?? -1}`,
);
const analysisProject = useAnalysisProject({
  capture,
  reloadCaptures,
  openCapture,
  failure,
  enabled: () => !!health.value.analysisProjects && !!displayedQuery.value,
  document: () => ({
    notes: research.notes.value,
    columns: research.columns.value,
    widths: workspaceLayout.widths.value,
    views: analysisTabs.value.map((item) => ({
      name: item.name,
      query: item.place?.query || displayedQuery.value || currentQuery(),
    })),
  }),
  apply: (document) => {
    research.hydrate(document);
    workspaceLayout.widths.value = document.widths;
    const safe = document.views.filter(
      (item): item is { name: string; query: WorkspaceQuery } =>
        !!item &&
        typeof item === "object" &&
        "name" in item &&
        typeof item.name === "string" &&
        "query" in item &&
        validQuery(item.query),
    );
    // 恢复保存的视图，但不自动执行查询或抢走当前详情；用户点击页签才应用。
    const place = readingPlace();
    if (place && safe.length) {
      analysisTabs.value = safe.slice(0, 5).map((item, index) => ({
        id: Date.now() + index,
        name: item.name,
        following: false,
        field: "",
        place: {
          ...place,
          query: item.query,
          page: 1,
          scrollTop: 0,
          selectedIndex: undefined,
          detailOpen: false,
        },
      }));
      activeAnalysisTab.value = analysisTabs.value[0].id;
    }
  },
});

const { projectSync, projectInput, importAnalysisProject } = analysisProject;

const messageLoader = useMessageLoader({
  capture,
  query: queryResults,
  filters,
  activity,
  supportsAnalysis: () => supportsAnalysis.value,
  fieldColumns: () => research.columns.value,
  grouping: () => messageGrouping.value,
  autoFollow,
  browsingHistory,
  viewingTail,
  unread,
  table: () => packetTable.value,
  revealRow,
});
const {
  loadMessages,
  cancelQuery,
  retryMessages,
  loading,
  interactiveLoading,
  jumpTarget,
  recentRows,
  emptyReasons,
  diagnosticTruncated,
} = messageLoader;

const messageHistory = useMessageHistory({
  capture,
  query: queryResults,
  filters,
  loader: messageLoader,
  selection,
  tab,
  autoFollow,
  browsingHistory,
  viewingTail,
  temporaryView,
  locatedRow,
  saveNavigationCheckpoint,
  tableScrollTop,
  revealRow,
  pauseHistory,
  pauseListForInteraction,
  clearBatch: () => {
    rowActions.value?.close();
    batchExcluding.value = false;
    selectedProtocols.value = [];
  },
});
const {
  contextScope,
  contextAnchor,
  searchOrigin,
  hasPrevious,
  hasNext,
  jumpToContext,
  returnToSearch,
  changePage,
  browseHistory,
  jumpLatest,
  loadLatestMessages,
  neighbour,
} = messageHistory;
const inspector = useMessageInspector({
  selection,
  tab,
  captureId: () => capture.value?.id,
  listQuery: () => displayedQuery.value?.q || searchOrigin.value?.query.q || "",
  restoring: () => restoringDetailReading.value,
});
const { detailQuery, focusedField, focusField } = inspector;
const relations = useMessageRelations({
  capture,
  selection,
  inspector,
  activity,
  rows,
  visibleRows: () => visibleRows.value,
  pauseListForInteraction,
  revealRow,
  jumpToContext,
  failure,
});
const {
  comparisonBase,
  comparisonDialog,
  comparingPrevious,
  selectedMissingFromList,
  selectedOutsideRetention,
  laterMessages,
  laterError,
  detailReturn,
  restoringDetailReading,
  restoreDetailReturn,
  viewLatestProtocol,
  locatePinnedMessage,
  comparePrevious,
  setComparisonBase,
} = relations;
function handleWorkspaceKey(event: KeyboardEvent) {
  if (mainWorkspace.value !== "captures") return;
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
    if (workspaceLayout.maximized.value) {
      void toggleDetailMaximize();
    } else if (focusMode.value) focusMode.value = false;
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
  const grouped =
    groupingRows.value.find((item) => item.index === target.index) || target;
  if (
    grouped.repeatOf !== undefined &&
    !expandedRepeats.value.has(grouped.repeatOf)
  ) {
    const next = new Set(expandedRepeats.value);
    next.add(grouped.repeatOf);
    expandedRepeats.value = next;
  }
  const id = capture.value?.id;
  locatedRow.value = target.index;
  highlightRow(target.index);
  void select(target);
  await nextTick();
  if (capture.value?.id !== id || selectedRowIndex.value !== target.index)
    return;
  const table = packetTable.value;
  table?.setCurrentRow(target);
  const position = visibleRows.value.findIndex(
    (item) => item.index === target.index,
  );
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
const captureFeed = useCaptureFeed({
  capture,
  captures,
  unread,
  autoFollow,
  browsingHistory,
  loader: messageLoader,
  activity,
  reloadCaptures,
  openCapture,
  failure,
});
const { refreshLive, openLiveCapture } = captureFeed;
async function openCapture(item: Capture, restore?: ReadingPlace) {
  if (
    capture.value &&
    capture.value.id !== item.id &&
    health.value.analysisProjects &&
    projectSync.ready.value
  ) {
    try {
      await nextTick();
      await projectSync.flush();
    } catch (error) {
      failure(error);
      return;
    }
  }
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
  messageLoader.clearDeferred();
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
        ? readingRange(restore.query.range, item.marks)
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
        page: Math.max(1, Math.ceil(total.value / DEFAULT_PAGE_SIZE)),
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
  searchHistory.value?.record();
  pauseListForInteraction("filter");
  actionFeedback.value = "";
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
const navigatingSearchResult = computed(
  () =>
    !!(
      displayedQuery.value?.q ||
      displayedQuery.value?.protocolId ||
      displayedQuery.value?.messageIndex
    ),
);
function openProtocol(row: Packet, event?: MouseEvent) {
  if (event?.shiftKey) {
    void tableRowClick(row, undefined, event);
    return;
  }
  // 搜索命中仍可一键跳转上下文；日常浏览直接读详情，不额外查询整页。
  if (navigatingSearchResult.value) void jumpToContext(row);
  else void tableRowClick(row, undefined, event || new MouseEvent("click"));
}
async function select(row: Packet | undefined) {
  if (!row || !capture.value) return;
  if (locatedRow.value !== row.index) locatedRow.value = undefined;
  await selectDetail(row);
}
watch(autoFollow, (value) => {
  if (switchingAnalysisTab.value || restoringNavigation.value) return;
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
    void loadLatestMessages();
});
onBeforeUnmount(() => {
  saveReadingPlace();

  if (actionFeedbackTimer) clearTimeout(actionFeedbackTimer);
  if (rowHighlightTimer) clearTimeout(rowHighlightTimer);

  window.removeEventListener("beforeunload", saveReadingPlace);

  window.removeEventListener("keydown", handleWorkspaceKey);

  messageGate.cancel();
  detailGate.cancel();
  messageLoader.clearDeferred();
});
async function captureImported(item: Capture) {
  await reloadCaptures();
  await openCapture(item);
}
onMounted(async () => {
  window.addEventListener("beforeunload", saveReadingPlace);
  window.addEventListener("keydown", handleWorkspaceKey);
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
  <div
    class="workbench"
    :class="{ 'focus-mode': focusMode && mainWorkspace === 'captures' }"
  >
    <WorkbenchHeader
      :controller="workbenchSettings"
      :health="health"
      :capture="capture"
      :selected-row-index="selectedRowIndex"
      :main-workspace="mainWorkspace"
      v-model:dark="dark"
      @captures="returnCaptureWorkspace"
      @catalog="openProtocolQuery()"
    />
    <LiveCapturePanel
      v-show="mainWorkspace === 'captures'"
      ref="livePanel"
      :follow="autoFollow"
      :pause-on-select="pauseOnSelect"
      @update:follow="setListFollow"
      @update:pause-on-select="pauseOnSelect = $event"
      :active-capture-id="capture?.id"
      @changed="refreshLive"
      @open="openLiveCapture"
      @stopped="captureStopped"
      @cleared="sessionCleared"
      @identity-changed="identityChanged"
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
    <ProtocolQueryPanel
      v-show="mainWorkspace === 'catalog'"
      :active="mainWorkspace === 'catalog'"
      :capture="capture"
      :target="protocolQueryTarget"
      :catalog-version="health.catalog.sha256"
      :return-label="selected ? `返回消息 #${selected.index}` : '返回抓包'"
      @close="returnCaptureWorkspace"
      @messages="showProtocolMessages"
      @settings="openSettings"
    />
    <div
      v-show="mainWorkspace === 'captures'"
      class="workspace"
      :class="{ 'sidebar-hidden': !sidebarVisible }"
    >
      <CaptureSidebar
        v-show="sidebarVisible"
        :captures="captures"
        :capture="capture"
        :unread="unread"
        v-model:busy="busy"
        :imported="captureImported"
        @collapse="sidebarCollapsed = true"
        @reload="reloadCaptures().catch(failure)"
        @open="openCapture"
        @delete="deleteCapture"
        @clear-error="errorText = ''"
        @error="failure"
      />
      <main class="main-area">
        <CaptureRecordToolbar
          :capture="capture"
          :total="total"
          :sidebar-visible="sidebarVisible"
          :search-origin="!!searchOrigin"
          :context-anchor="contextAnchor"
          :context-scope="contextScope"
          :located-row="locatedRow"
          :is-following="isFollowing"
          :pause-description="pauseDescription"
          :analysis-projects="health.analysisProjects"
          :operations="captureOperations"
          :project="analysisProject"
          :selection="selection"
          v-model:sidebar-collapsed="sidebarCollapsed"
          v-model:focus-mode="focusMode"
          v-model:connection-identity-dialog="connectionIdentityDialog"
          v-model:workspace-view="workspaceView"
          :actions="{ openCapture, jumpToContext, returnToSearch, saveCapture }"
        />
        <div
          v-if="health.ready && !supportsAnalysis"
          class="warning-bar compatibility-bar"
          role="status"
        >
          页面需要新版后端才能使用分析工具。请先保存临时抓包，再重启工作台并刷新页面。<el-button
            text
            @click="checkBackend"
            >重新检查</el-button
          >
        </div>
        <AnalysisTabsBar
          v-if="capture"
          :capture="capture"
          :summary="captures.find((item) => item.id === capture?.id)"
          :latest="activity.latest.value"
          :tabs="analysisTabState"
          :project-sync="projectSync"
          :analysis-projects="health.analysisProjects"
          :actions="{
            switchAnalysisTab,
            closeAnalysisTab,
            renameAnalysisTab,
            newAnalysisTab,
            confirmReloadProject,
          }"
        />
        <div
          v-if="displayedQuery?.range.startsWith('recent:')"
          class="temporary-view-bar"
        >
          <span
            >{{ temporaryView?.name || "临时查看" }} ·
            {{
              readingRange(displayedQuery.range, capture?.marks || [])?.name
            }}（临时查看，原筛选保留）</span
          ><el-button text size="small" @click="jumpLatest"
            >返回实时 / 最新</el-button
          >
        </div>
        <CaptureFilterToolbar
          ref="searchHistory"
          :filters="filters"
          :capture="capture"
          :dictionary="
            capture?.catalog?.sha256 || health.catalog.sha256 || 'unloaded'
          "
          :columns="columns"
          v-model:detail-dock="detailDock"
          :supports-analysis="supportsAnalysis"
          :interactive-loading="interactiveLoading"
          :has-undo="!!undoPlace"
          :batch-excluding="batchExcluding"
          @views="viewDialog = true"
          :actions="{
            filter,
            openResearch,
            undoQuickQuery,
            toggleBatchMode,
            resetLayout,
            removeFilter,
            quickQuery,
            resetFilters,
          }"
        />
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
        <LiveAttentionBar
          v-if="
            capture &&
            (capture.liveState === 'CAPTURING' ||
              protocolWatch.choices.value.length ||
              displayedActivity?.total)
          "
          :running="capture.liveState === 'CAPTURING'"
          :following="isFollowing"
          :reason="pauseDescription"
          :delta="displayedActivity"
          :watched="protocolWatch.choices.value"
          :activity-error="activityWindow.error.value || activity.error.value"
          :loading="activityWindow.loading.value"
          :updated-at="activityWindow.updatedAt.value"
          :restart-required="activityWindow.restartRequired.value"
          @latest="jumpLatest"
          @activity="viewActivity"
          @watch="viewWatched"
          @unwatch="protocolWatch.remove"
          @retry="
            () => {
              activity.refresh();
              activityWindow.refresh();
            }
          "
        />
        <div
          v-if="fieldWatch.items.value.length"
          class="field-watchbar"
          aria-label="关注字段变化"
        >
          <span>字段关注</span
          ><span
            v-for="item in fieldWatch.items.value"
            :key="item.key"
            class="field-watch-item"
            ><button
              :class="{ changed: item.changed }"
              :title="`${item.protocolName} · ${item.direction} · ${item.path} · 连接 ${item.connection}`"
              @click="locateWatchedField(item.key)"
            >
              {{ item.label }}：{{
                item.changed
                  ? `${item.first} → ${item.last}`
                  : (item.last ?? "等待采样")
              }}{{ item.incomplete ? "（保留范围不完整）" : ""
              }}{{ item.error ? "（监视失败）" : "" }}</button
            ><button
              @click="fieldWatch.remove(item.key)"
              :aria-label="`取消关注字段 ${item.label}`"
            >
              ×
            </button></span
          ><el-button
            v-if="fieldWatch.items.value.some((item) => item.error)"
            text
            size="small"
            @click="fieldWatch.refresh"
            >重试字段关注</el-button
          >
        </div>
        <div
          v-if="navigationReturn?.place.captureId === capture?.id"
          class="reading-return-bar"
        >
          <el-button text size="small" @click="returnReadingCheckpoint"
            >返回刚才位置{{
              navigationReturn?.place.selectedIndex !== undefined
                ? ` · 消息 #${navigationReturn.place.selectedIndex}`
                : ""
            }}</el-button
          ><el-button text size="small" @click="navigationReturn = undefined"
            >关闭返回入口</el-button
          >
        </div>
        <div v-if="selectedOperationRange" class="selected-operation-bar">
          <b
            >已选原消息 #{{ selectedOperationRange.from }}–{{
              selectedOperationRange.to
            }}</b
          ><span class="muted"
            >按原编号选区间，包含当前筛选隐藏的消息；原内容未改动。</span
          ><el-button size="small" @click="useSelectedRange('mark')"
            >标记区间</el-button
          ><el-button size="small" @click="useSelectedRange('snapshot')"
            >保存区间快照</el-button
          ><el-button size="small" @click="useSelectedRange('review')"
            >操作回顾</el-button
          ><el-button
            text
            size="small"
            @click="selectedOperationRange = undefined"
            >取消范围</el-button
          >
        </div>
        <div
          v-if="actionFeedback && undoPlace"
          class="local-action-feedback"
          role="status"
        >
          {{ actionFeedback }}
          <el-button text size="small" @click="undoQuickQuery">撤销</el-button>
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
                <CaptureMessageTable
                  ref="messageTable"
                  :rows="rows"
                  :visible-rows="visibleRows"
                  :capture="capture"
                  :busy="busy"
                  :table-render-deps="tableRenderDeps"
                  :highlighted-row="highlightedRow"
                  :recent-rows="recentRows"
                  :selected-operation-range="selectedOperationRange"
                  :batch-excluding="batchExcluding"
                  :selected-protocols="selectedProtocols"
                  :columns="columns"
                  :field-columns="research.columns.value"
                  :workspace-layout="workspaceLayout"
                  :detail-query="detailQuery"
                  :displayed-query="displayedQuery"
                  :navigating-search-result="navigatingSearchResult"
                  :fold-repeats="foldRepeats"
                  :grouped-repeats="groupedRepeats"
                  :expanded-repeats="expandedRepeats"
                  :message-grouping="messageGrouping"
                  :is-watched="protocolWatch.has"
                  :status-text="statusText"
                  :actions="{
                    tableRowClick,
                    resetColumnFromHeader,
                    toggleProtocolSelection,
                    toggleWatch,
                    openProtocol,
                    openGroupPreview,
                    excludeProtocol,
                    pauseListForInteraction,
                    jumpToContext,
                    onlyProtocol,
                  }"
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
                      <div
                        v-for="reason in emptyReasons"
                        :key="reason.field"
                        class="empty-reason"
                      >
                        <span>{{ reason.description }}</span
                        ><el-button
                          size="small"
                          @click="removeBlockingFilter(reason)"
                          >{{ reason.action }}</el-button
                        >
                      </div>
                      <p v-if="diagnosticTruncated" class="muted">
                        诊断已达到扫描预算或跳过大包正文，未找到原因不代表协议不存在。
                      </p>
                      <el-button
                        v-if="total > 0 && !rows.length && !loading"
                        size="small"
                        @click="loadMessages(false, false, { page: 1 })"
                        >返回结果第一页</el-button
                      >
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
                </CaptureMessageTable>
                <div class="pagination">
                  <el-button
                    v-if="workspaceLayout.resetHint.value"
                    text
                    size="small"
                    @click="
                      workspaceLayout.reset();
                      tableRevision++;
                    "
                    title="也可双击表头右边缘恢复对应列宽"
                    >恢复列宽</el-button
                  >
                  <div class="message-grouping">
                    <span>本页显示：</span
                    ><el-select
                      v-model="messageGrouping"
                      :disabled="!supportsAnalysis"
                      size="small"
                      aria-label="本页消息显示方式"
                      @change="toggleFold"
                    >
                      <el-option value="none" label="逐条消息" />
                      <el-option value="content" label="相同内容折叠" />
                      <el-option
                        value="protocol"
                        label="同协议归组（内容可不同）"
                      />
                    </el-select>
                  </div>
                  <span
                    v-if="messageGrouping === 'protocol'"
                    class="grouping-hint"
                    title="同连接、方向、协议和解码状态归组；可能跨越穿插消息，不能按折叠行推断完整时间顺序"
                    >内容可不同，展开核对原编号</span
                  >
                  <span class="muted">{{
                    viewingTail
                      ? `${isFollowing ? "最新" : "暂停窗口"} ${rows.length} 条 / 匹配 ${total} 条`
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
                    :page-size="DEFAULT_PAGE_SIZE"
                    :total="total"
                    layout="total, prev, pager, next"
                    size="small"
                    @update:current-page="changePage"
                  />
                </div>
              </section>
            </template>
            <template #secondary>
              <MessageDetailPanel
                :selection="selection"
                :inspector="inspector"
                :capture="capture"
                :maximized="workspaceLayout.maximized.value"
                :supports-analysis="supportsAnalysis"
                :has-previous="hasPrevious"
                :has-next="hasNext"
                :interactive-loading="interactiveLoading"
                :comparing-previous="comparingPrevious"
                :query-metrics="queryMetrics"
                :has-search-origin="!!searchOrigin"
                :has-active-filters="!!activeFilters.length"
                :detail-return="detailReturn"
                :selected-outside-retention="selectedOutsideRetention"
                :selected-missing-from-list="selectedMissingFromList"
                :later-messages="laterMessages"
                :later-error="laterError"
                :field-annotations="fieldAnnotations"
                v-model:comparison-base="comparisonBase"
                @compare="comparisonDialog = true"
                :actions="{
                  openProtocolQuery,
                  neighbour,
                  comparePrevious,
                  editNote,
                  setComparisonBase,
                  restoreDetailReturn,
                  locatePinnedMessage,
                  viewLatestProtocol,
                  select,
                  addFieldColumn,
                  watchField,
                  toggleDetailMaximize,
                }"
              />
            </template>
          </SplitPane>
        </div>
      </main>
    </div>
    <ConnectionIdentityPanel
      v-model="connectionIdentityDialog"
      :capture-id="capture?.id"
      @saved="identityChanged"
    />
    <SavedViewsDialog
      :controller="savedViewState"
      :can-save="!!displayedQuery"
      :can-apply="!!capture"
    />
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
    <input
      ref="projectInput"
      type="file"
      accept=".json,application/json"
      hidden
      @change="importAnalysisProject"
    />
    <MessageGroupPreview
      v-if="groupPreviewOpen && previewGroup !== undefined"
      :rows="groupedRepeats.get(previewGroup) || []"
      :protocol="messageGrouping === 'protocol'"
      :expanded="expandedRepeats.has(previewGroup)"
      @close="
        groupPreviewOpen = false;
        closeGroupPreview();
      "
      @inspect="inspectGroupMessage"
      @expand="
        previewGroup !== undefined && toggleRepeat(previewGroup);
        groupPreviewOpen = false;
        closeGroupPreview();
      "
    />
    <ResearchPanel
      v-if="researchOpen && capture && supportsAnalysis"
      :capture="capture"
      :captures="captures"
      :query="displayedQuery || currentQuery()"
      :columns="research.columns.value"
      :notes="research.notes.value"
      :selected="selected"
      :mode="researchMode"
      :initial-range="lastOperationRange"
      @close="researchOpen = false"
      @locate="locateResearch"
      @range="showResearchRange"
      @remove-column="removeFieldColumn"
      @remove-note="removeNote"
      @note="editNote"
      @watch-field="watchField"
      @save-range="
        researchOpen = false;
        markDialog = true;
      "
    />
    <MessageNoteDialog :controller="researchWorkspace" />
    <CaptureOperationDialog
      :controller="captureOperations"
      :capture="capture"
      :supports-analysis="supportsAnalysis"
      :busy="busy"
      @review="openResearch('review')"
    />
    <WorkbenchSettingsDialog
      :controller="workbenchSettings"
      :catalog-count="health.catalog.messageCount"
    />
  </div>
</template>
