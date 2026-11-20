import { computed, ref } from "vue";
import type { Capture } from "./types.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
import { readingRange } from "./liveReading.ts";
import { statusText } from "./capturePresentation.ts";
/** 草稿与已生效筛选各自保留；标签只根据最近成功查询生成，修改草稿不会提前改变结果。 */
export function useWorkspaceFilters(options: {
  capture: () => Capture | undefined;
  query: () => WorkspaceQuery | undefined;
}) {
  const capture = computed(options.capture),
    displayedQuery = computed(options.query);
  const search = ref("");
  const protocolId = ref("");
  const messageIndex = ref("");
  const appliedProtocolId = ref("");
  const appliedMessageIndex = ref("");
  const include = ref("");
  const exclude = ref("");
  const appliedInclude = ref("");
  const appliedExclude = ref("");
  const direction = ref("");
  const status = ref("");
  const connection = ref("");
  const hideHeartbeat = ref(true);
  const selectedRange = ref("");
  const appliedSearch = ref("");
  const order = ref(localStorage.getItem("analyzer.order") || "desc");
  const expandedTags = ref(false);
  const advancedFilters = ref(false);
  const tableSettings = ref(false);
  const filterDialog = ref(false);
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
    if (q.range && !q.range.startsWith("recent:"))
      result.push({
        key: "range",
        label: `区间：${readingRange(q.range, capture.value?.marks || [])?.name || q.range}`,
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

  return {
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
    tableSettings,
    filterDialog,
    draftQuery,
    hasDraft,
    activeFilters,
    visibleFilters,
    currentQuery,
    applyQuery,
    displayedQuery,
  };
}
