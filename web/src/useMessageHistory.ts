import { computed, ref, shallowRef, type Ref, type ShallowRef } from "vue";
import { ElMessage } from "element-plus";
import { DEFAULT_PAGE_SIZE, DETAIL_REQUEST_TIMEOUT_MS } from "./constants.ts";
import { api, type Capture, type Packet, type Page } from "./types.ts";
import {
  contextQuery,
  contextRankParams,
  type WorkspaceQuery,
} from "./workspaceInteractions.ts";
import { pageContaining, neighbourPage } from "./analysisWorkspace.ts";
import { exitTemporaryQuery } from "./analysisTools.ts";
import type { TemporaryView } from "./useAnalysisTabs.ts";
import type { useMessageLoader } from "./useMessageLoader.ts";
import type { useQueryResults } from "./useQueryResults.ts";
import type { useWorkspaceFilters } from "./useWorkspaceFilters.ts";
import type { useMessageSelection } from "./useMessageSelection.ts";
/** 上下文、跨页和返回搜索只在查询成功时提交位置，任何失败保留原筛选和详情。 */
export function useMessageHistory(options: {
  capture: ShallowRef<Capture | undefined>;
  query: ReturnType<typeof useQueryResults>;
  filters: ReturnType<typeof useWorkspaceFilters>;
  loader: ReturnType<typeof useMessageLoader>;
  selection: ReturnType<typeof useMessageSelection>;
  tab: Ref<string>;
  autoFollow: Ref<boolean>;
  browsingHistory: Ref<boolean>;
  viewingTail: Ref<boolean>;
  temporaryView: ShallowRef<TemporaryView | undefined>;
  locatedRow: Ref<number | undefined>;
  saveNavigationCheckpoint: () => void;
  tableScrollTop: () => number;
  revealRow: (packet: Packet) => Promise<void>;
  pauseHistory: () => void;
  pauseListForInteraction: (reason?: string) => void;
  clearBatch: () => void;
}) {
  const {
    capture,
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
  } = options;
  const {
    rows,
    total,
    page,
    pageOffset,
    displayedPage,
    displayedQuery,
    pendingQuery,
    queryError,
    queryPublication,
    messageGate,
  } = options.query;
  const {
    search,
    protocolId,
    messageIndex,
    include,
    exclude,
    order,
    currentQuery,
    applyQuery,
  } = options.filters;
  const {
    loadMessages,
    cancelQuery,
    retryAction,
    loading,
    interactiveLoading,
    jumpTarget,
  } = options.loader;
  const { selectedRowIndex } = options.selection;
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
  const hasPrevious = computed(
    () =>
      !!neighbourPage(
        displayedPage.value,
        rows.value.findIndex((item) => item.index === selectedRowIndex.value),
        rows.value.length,
        total.value,
        -1,
        DEFAULT_PAGE_SIZE,
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
        DEFAULT_PAGE_SIZE,
        pageOffset.value,
      ),
  );
  async function jumpToContext(
    row: Packet,
    scope: "connection" | "all" = contextScope.value,
    hideContextHeartbeat?: boolean,
  ): Promise<void> {
    if (!capture.value) return;
    saveNavigationCheckpoint();
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
    if (hideContextHeartbeat !== undefined)
      query.hideHeartbeat = hideContextHeartbeat;
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
    retryAction.value = () => jumpToContext(row, scope, hideContextHeartbeat);
    const timer = setTimeout(() => {
      if (request.current())
        cancelQuery("上下文定位超时，已保留原结果，可重试");
    }, DETAIL_REQUEST_TIMEOUT_MS);
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
    pauseHistory();
    if (value === displayedPage.value && !loading.value) return;
    jumpTarget.value = undefined;
    locatedRow.value = undefined;
    browsingHistory.value = true;
    page.value = value;
    void loadMessages();
  }
  function browseHistory() {
    pauseHistory();
    browsingHistory.value = true;
    page.value =
      (displayedQuery.value?.order || order.value) === "desc"
        ? 1
        : Math.max(1, Math.ceil(total.value / DEFAULT_PAGE_SIZE));
    void loadMessages();
  }
  function jumpLatest() {
    saveNavigationCheckpoint();
    options.clearBatch();
    browsingHistory.value = false;
    if (!autoFollow.value) autoFollow.value = true;
    else void loadLatestMessages();
  }
  function loadLatestMessages() {
    const query = exitTemporaryQuery(
      displayedQuery.value || currentQuery(),
      temporaryView.value?.origin,
      temporaryView.value?.applied,
    );
    return loadMessages(true, false, {
      query,
      onCommit: () => {
        applyQuery(query);
        temporaryView.value = undefined;
      },
    });
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
      DEFAULT_PAGE_SIZE,
      pageOffset.value,
    );
    if (!next) return;
    pauseListForInteraction("history");
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

  return {
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
  };
}
