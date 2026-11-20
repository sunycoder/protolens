import { onScopeDispose, ref, shallowRef, watch } from "vue";
import { api, ApiError, type Capture } from "./types.ts";
import { RequestGate } from "./workspaceRefresh.ts";
import {
  activityWindowParams,
  latestQuery,
  visibleActivityDelta,
  type ActivityDelta,
  type CaptureActivity,
} from "./liveReading.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";

/** 通知与列表使用同一筛选，异步统计只能发布到对应记录、区间和筛选；切换时立即撤下旧入口。 */
export function useActivityWindow(
  capture: () => Capture | undefined,
  delta: () => ActivityDelta | undefined,
  query: () => WorkspaceQuery,
  snapshot: () => CaptureActivity | undefined,
  enabled: () => boolean = () => true,
) {
  const value = shallowRef<ActivityDelta>();
  const source = shallowRef<CaptureActivity>();
  const loading = ref(false);
  const error = ref("");
  const restartRequired = ref(false);
  const gate = new RequestGate();
  let timer: ReturnType<typeof setTimeout> | undefined;
  function refresh() {
    if (timer) clearTimeout(timer);
    const request = gate.begin();
    const record = capture(),
      pending = delta(),
      original = snapshot();
    value.value = undefined;
    source.value = undefined;
    error.value = "";
    restartRequired.value = false;
    loading.value = false;
    if (!enabled()) {
      restartRequired.value = true;
      error.value = "后端尚未提供活动统计接口";
      return;
    }
    if (!record || !pending || !pending.total) return;
    const params = activityWindowParams(pending, query(), record.marks);
    if (!params) {
      value.value = {
        ...pending,
        total: 0,
        attention: 0,
        protocolCount: 0,
        firstSeen: [],
      };
      return;
    }
    loading.value = true;
    // 合并同一轮列表、活动及筛选变化；只扫描新增窗口，不循环读取整份消息详情。
    timer = setTimeout(async () => {
      try {
        const result = await api<CaptureActivity>(
          `/api/captures/${record.id}/activity-window?${params}`,
          { signal: request.signal },
        );
        if (
          !request.current() ||
          result.captureId !== record.id ||
          result.clearVersion !== original?.clearVersion
        )
          return;
        value.value = visibleActivityDelta(pending, result);
        source.value = original;
      } catch (cause) {
        if (request.current()) {
          restartRequired.value =
            cause instanceof ApiError && cause.status === 404;
          error.value = String(cause);
        }
      } finally {
        if (request.current()) loading.value = false;
      }
    }, 120);
  }
  // 使用稳定签名，记录备注变化不重复查询；通知临时区间的变化也不清空刚统计的入口。
  watch(
    () =>
      JSON.stringify([
        capture()?.id,
        capture()?.firstIndex,
        capture()?.clearVersion,
        delta(),
        latestQuery(query()),
        capture()?.marks,
        enabled(),
      ]),
    refresh,
    { immediate: true },
  );
  onScopeDispose(() => {
    if (timer) clearTimeout(timer);
    gate.cancel();
  });
  return { value, source, loading, error, restartRequired, refresh };
}
