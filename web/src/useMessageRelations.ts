import {
  computed,
  nextTick,
  ref,
  shallowRef,
  watch,
  onScopeDispose,
  type ShallowRef,
} from "vue";
import { ElMessage } from "element-plus";
import { api, type Capture, type Packet } from "./types.ts";
import { RequestGate } from "./workspaceRefresh.ts";
import type { useMessageSelection } from "./useMessageSelection.ts";
import type { useMessageInspector } from "./useMessageInspector.ts";
import type { useCaptureActivity } from "./useCaptureActivity.ts";
/** 后续消息只作同连接、同方向、同协议检索；保留固定样本与返回位置，不推断请求响应关系。 */
export function useMessageRelations(options: {
  capture: ShallowRef<Capture | undefined>;
  selection: ReturnType<typeof useMessageSelection>;
  inspector: ReturnType<typeof useMessageInspector>;
  activity: ReturnType<typeof useCaptureActivity>;
  rows: ShallowRef<Packet[]>;
  visibleRows: () => Packet[];
  pauseListForInteraction: (reason?: string) => void;
  revealRow: (packet: Packet) => Promise<void>;
  jumpToContext: (
    packet: Packet,
    scope?: "connection" | "all",
    hideHeartbeat?: boolean,
  ) => Promise<void>;
  failure: (error: unknown) => void;
}) {
  const {
    capture,
    activity,
    rows,
    pauseListForInteraction,
    revealRow,
    jumpToContext,
    failure,
  } = options;
  const visibleRows = computed(options.visibleRows);
  const {
    selected,
    selectedRowIndex,
    detailGate,
    detailTarget,
    detailLoading,
    detailError,
    detailOpen,
    selectDetail,
  } = options.selection;
  const { tab, focusedField, focusField } = options.inspector;
  const comparisonBase = shallowRef<Packet>();
  const comparisonDialog = ref(false);
  const comparingPrevious = ref(false);
  const selectedMissingFromList = computed(
    () =>
      !!selected.value &&
      !visibleRows.value.some((row) => row.index === selected.value!.index),
  );
  const selectedOutsideRetention = computed(
    () =>
      !!selected.value &&
      selected.value.index <
        (activity.latest.value?.firstRetained ??
          capture.value?.firstIndex ??
          0),
  );
  const laterMessages = shallowRef<{
    count: number;
    truncated: boolean;
    latest?: Packet;
  }>();
  const laterError = ref("");
  const laterGate = new RequestGate();
  const detailReturn = shallowRef<{
    captureId: string;
    packet: Packet;
    tab: string;
    field: string;
  }>();
  const restoringDetailReading = ref(false);
  watch(
    () => capture.value?.id,
    () => {
      laterGate.cancel();
      laterMessages.value = undefined;
      detailReturn.value = undefined;
    },
  );
  watch(
    () => [
      selected.value?.index,
      selected.value?.connection,
      activity.latest.value?.lastIndex,
      activity.latest.value?.clearVersion,
    ],
    async (next, previous) => {
      if (
        !previous ||
        next[0] !== previous[0] ||
        next[1] !== previous[1] ||
        next[3] !== previous[3]
      )
        laterMessages.value = undefined;
      const packet = selected.value,
        id = capture.value?.id;
      const request = laterGate.begin();
      if (
        !packet ||
        !id ||
        !packet.connection ||
        !packet.direction ||
        (!packet.protocolName && packet.protocolId === undefined)
      ) {
        laterMessages.value = undefined;
        return;
      }
      const params = new URLSearchParams({
        after: String(packet.index),
        protocolId:
          packet.protocolId === undefined ? "" : String(packet.protocolId),
        protocolName: packet.protocolName || "",
        connection: packet.connection,
        direction: packet.direction,
      });
      try {
        const result = await api<{
          count: number;
          truncated: boolean;
          latest?: Packet;
        }>(`/api/captures/${id}/latest-protocol?${params}`, {
          signal: request.signal,
        });
        if (
          request.current() &&
          selected.value === packet &&
          capture.value?.id === id
        ) {
          laterMessages.value = result;
          laterError.value = "";
        }
      } catch (error) {
        if (request.current()) laterError.value = String(error);
      }
    },
  );
  async function restoreDetailReturn() {
    const place = detailReturn.value;
    if (!place || place.captureId !== capture.value?.id) return;
    restoringDetailReading.value = true;
    detailGate.cancel();
    selected.value = place.packet;
    selectedRowIndex.value = place.packet.index;
    detailTarget.value = place.packet;
    detailLoading.value = false;
    detailError.value = "";
    detailOpen.value = true;
    detailReturn.value = undefined;
    await nextTick();
    restoringDetailReading.value = false;
    tab.value = place.tab;
    focusedField.value = place.field;
    if (place.tab === "tree" && place.field)
      await focusField(place.field, false);
  }
  async function viewLatestProtocol(compare = false) {
    const next = laterMessages.value?.latest,
      packet = selected.value,
      id = capture.value?.id;
    if (!next || !packet || !id) return;
    const previous = {
      captureId: id,
      packet,
      tab: tab.value,
      field: focusedField.value,
    };
    if (!detailReturn.value) detailReturn.value = previous;
    await selectDetail(next);
    if (capture.value?.id !== id) return;
    if (!selected.value || selected.value.index !== next.index) {
      const message = detailError.value;
      detailReturn.value = previous;
      await restoreDetailReturn();
      laterError.value = message || "后续消息已不在保留范围，仍保留原详情";
      return;
    }
    if (compare) {
      comparisonBase.value = packet;
      comparisonDialog.value = true;
    }
  }
  async function locatePinnedMessage() {
    if (!selected.value) return;
    if (selectedOutsideRetention.value) {
      laterError.value = "原消息已不在保留范围，已打开的详情继续保留";
      return;
    }
    pauseListForInteraction("locate");
    const row = rows.value.find((row) => row.index === selected.value!.index);
    if (row) {
      await revealRow(row);
      return;
    }
    await jumpToContext(selected.value, "connection", false);
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
  function setComparisonBase() {
    if (!selected.value) return;
    comparisonBase.value = selected.value;
    ElMessage.success(
      `已固定消息 #${selected.value.index}，选择另一条消息后点击“对比变化”`,
    );
  }

  onScopeDispose(() => laterGate.cancel());
  return {
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
  };
}
