import {
  DETAIL_REQUEST_TIMEOUT_MS,
  DETAIL_PREFETCH_MAX_BYTES,
} from "./constants.ts";
import { ref, shallowRef } from "vue";
import { RequestGate } from "./workspaceRefresh";
import { api, type Capture, type Packet } from "./types";

/** 详情请求、缓存和选中状态独立于列表刷新；迟到响应不能覆盖用户新选择。 */
export function useMessageSelection(
  currentCapture: () => Capture | undefined,
  onRead: () => void,
) {
  const selected = shallowRef<Packet>();
  const selectedRowIndex = ref<number>();
  const detailTarget = shallowRef<Packet>();
  const detailLoading = ref(false);
  const detailError = ref("");
  const detailOpen = ref(false);
  const detailGate = new RequestGate();
  const detailCache = new Map<string, Packet>();
  const detailMs = ref(0);

  async function selectDetail(row: Packet) {
    const capture = currentCapture();
    if (!capture) return;
    onRead();
    detailOpen.value = true;
    selectedRowIndex.value = row.index;
    detailTarget.value = row;
    detailError.value = "";
    const id = capture.id;
    const key = `${id}:${row.index}`;
    const request = detailGate.begin();
    const start = performance.now();
    const cached = detailCache.get(key);
    if (cached) {
      selected.value = cached;
      detailLoading.value = false;
      detailMs.value = performance.now() - start;
      return;
    }
    selected.value = undefined;
    detailLoading.value = true;
    const timer = setTimeout(() => {
      if (!request.current()) return;
      detailGate.cancel();
      detailLoading.value = false;
      detailError.value = `消息 #${row.index} 详情等待超时，可重试`;
    }, DETAIL_REQUEST_TIMEOUT_MS);
    try {
      const detail = await api<Packet>(
        `/api/captures/${id}/messages/${row.index}`,
        {
          signal: request.signal,
        },
      );
      if (!request.current() || currentCapture()?.id !== id) return;
      selected.value = detail;
      detailMs.value = performance.now() - start;
      // 原始字节缺失时不缓存，避免将未知大小的大包当作小包长期驻留。
      if (
        detail.bytes !== undefined &&
        detail.bytes <= DETAIL_PREFETCH_MAX_BYTES
      ) {
        detailCache.set(key, detail);
        if (detailCache.size > 24)
          detailCache.delete(detailCache.keys().next().value!);
      }
    } catch (error) {
      if (request.current())
        detailError.value = `消息 #${row.index} 加载失败：${String(error)}`;
    } finally {
      clearTimeout(timer);
      if (request.current()) detailLoading.value = false;
    }
  }
  return {
    selected,
    selectedRowIndex,
    detailTarget,
    detailLoading,
    detailError,
    detailOpen,
    detailGate,
    detailCache,
    detailMs,
    selectDetail,
  };
}
