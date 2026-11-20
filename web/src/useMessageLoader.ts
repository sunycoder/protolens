import {
  nextTick,
  ref,
  shallowRef,
  onScopeDispose,
  type Ref,
  type ShallowRef,
} from "vue";
import type { TableInstance } from "element-plus";
import { ElMessage } from "element-plus";
import { DEFAULT_PAGE_SIZE, MESSAGE_REQUEST_TIMEOUT_MS } from "./constants.ts";
import { api, type Capture, type Packet, type Page } from "./types.ts";
import { readingRange, type EmptyReason } from "./liveReading.ts";
import { reconcileRows } from "./workspaceRefresh.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
import type { FieldColumn, MessageGrouping } from "./analysisTools.ts";
import type { useQueryResults } from "./useQueryResults.ts";
import type { useWorkspaceFilters } from "./useWorkspaceFilters.ts";
import type { useCaptureActivity } from "./useCaptureActivity.ts";
export interface MessageNavigation {
  position?: number;
  index?: number;
  scrollTop?: number;
  query?: WorkspaceQuery;
  page?: number;
  onCommit?: () => void;
}

/** 只管理消息查询的请求、发布、超时与合并刷新；导航成功后的位置恢复由调用方明确传入。 */
export function useMessageLoader(options: {
  capture: ShallowRef<Capture | undefined>;
  query: ReturnType<typeof useQueryResults>;
  filters: ReturnType<typeof useWorkspaceFilters>;
  activity: ReturnType<typeof useCaptureActivity>;
  supportsAnalysis: () => boolean;
  fieldColumns: () => FieldColumn[];
  grouping: () => MessageGrouping;
  autoFollow: Ref<boolean>;
  browsingHistory: Ref<boolean>;
  viewingTail: Ref<boolean>;
  unread: ShallowRef<Record<string, number>>;
  table: () => TableInstance | undefined;
  revealRow: (packet: Packet) => Promise<void>;
}) {
  const {
    capture,
    activity,
    autoFollow,
    browsingHistory,
    viewingTail,
    unread,
    revealRow,
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
    displayedAt,
    queryPublication,
    messageGate,
    queryMetrics,
  } = options.query;
  const { currentQuery } = options.filters;
  let pendingLive = false,
    refreshTimer: ReturnType<typeof setTimeout> | undefined,
    recentRowsTimer: ReturnType<typeof setTimeout> | undefined;
  const loading = ref(false);
  const interactiveLoading = ref(false);
  const jumpTarget = ref<number>();
  const recentRows = shallowRef<Set<number>>(new Set());
  const emptyReasons = shallowRef<EmptyReason[]>([]);
  const diagnosticTruncated = ref(false);
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
    }, MESSAGE_REQUEST_TIMEOUT_MS);
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
      offset: String((requestedPage - 1) * DEFAULT_PAGE_SIZE),
      limit: String(DEFAULT_PAGE_SIZE),
      tail: String(tail),
      ...(options.supportsAnalysis()
        ? {
            fields: JSON.stringify(
              options.fieldColumns().map((field) => field.path),
            ),
            fold: String(options.grouping() === "content"),
          }
        : {}),
    });
    if (query.range) {
      const mark = readingRange(query.range, capture.value.marks);
      if (!mark) {
        cancelQuery("当前区间已不可用，请取消区间限制后重试");
        return;
      }
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
      const previousLast = rows.value.reduce(
        (last, row) => Math.max(last, row.index),
        -1,
      );
      if (background && previousLast >= 0) {
        recentRows.value = new Set(
          result.items
            .filter((row) => row.index > previousLast)
            .slice(0, 20)
            .map((row) => row.index),
        );
        if (recentRowsTimer) clearTimeout(recentRowsTimer);
        recentRowsTimer = setTimeout(
          () => (recentRows.value = new Set()),
          1800,
        );
      } else {
        // 主动查询历史或切换记录后，旧的新增提示不能落在本次结果上。
        if (recentRowsTimer) clearTimeout(recentRowsTimer);
        recentRows.value = new Set();
      }
      rows.value = reconcileRows(rows.value, result.items);
      emptyReasons.value = result.emptyReasons || [];
      diagnosticTruncated.value = !!result.diagnosticTruncated;
      activity.publish(
        result.activity,
        tail && autoFollow.value && !browsingHistory.value,
      );
      total.value = result.total;
      pageOffset.value =
        result.offset ?? (requestedPage - 1) * DEFAULT_PAGE_SIZE;
      displayedPage.value =
        Math.floor(pageOffset.value / DEFAULT_PAGE_SIZE) + 1;
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
          options.table()?.setScrollTop(navigation.scrollTop);
      }
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
  function cancelQuery(message = "已取消本次查询，保留上次成功结果") {
    messageGate.cancel();
    queryPublication.cancel();
    loading.value = false;
    interactiveLoading.value = false;
    pendingQuery.value = undefined;
    queryError.value = message;
  }

  function queueLive() {
    pendingLive = true;
  }
  function clearDeferred() {
    pendingLive = false;
    if (refreshTimer) clearTimeout(refreshTimer);
    refreshTimer = undefined;
  }
  onScopeDispose(() => {
    clearDeferred();
    if (recentRowsTimer) clearTimeout(recentRowsTimer);
    messageGate.cancel();
  });
  return {
    loadMessages,
    cancelQuery,
    retryAction,
    retryMessages,
    loading,
    interactiveLoading,
    jumpTarget,
    recentRows,
    emptyReasons,
    diagnosticTruncated,
    queueLive,
    clearDeferred,
  };
}
