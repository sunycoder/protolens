import { computed, shallowRef } from "vue";
import { api, type Capture } from "./types.ts";
import {
  activityWindowParams,
  latestQuery,
  visibleActivityDelta,
  type ActivityDelta,
  type CaptureActivity,
} from "./liveReading.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
import { useStableRefresh } from "./useStableRefresh.ts";

/** 记录和筛选不变时保留已确认统计；消息持续到达只排队一次后续刷新，不产生取消饥饿。 */
export function useActivityWindow(
  capture: () => Capture | undefined,
  delta: () => ActivityDelta | undefined,
  query: () => WorkspaceQuery,
  snapshot: () => CaptureActivity | undefined,
  enabled: () => boolean = () => true,
  readingScope: () => string = () => "",
) {
  const value = shallowRef<ActivityDelta>(),
    source = shallowRef<CaptureActivity>();
  const state = useStableRefresh({
    scope: () =>
      JSON.stringify([
        capture()?.id,
        capture()?.clearVersion,
        snapshot()?.clearVersion,
        snapshot()?.mode,
        readingScope(),
        latestQuery(query()),
        capture()?.marks,
        enabled(),
      ]),
    signature: () => JSON.stringify([delta(), capture()?.firstIndex]),
    input: () => {
      const record = capture(),
        pending = delta(),
        original = snapshot();
      if (!enabled() || !record || !pending?.total) {
        if (!pending?.total) {
          value.value = undefined;
          source.value = undefined;
        }
        return;
      }
      return {
        record,
        pending,
        original,
        params: activityWindowParams(pending, query(), record.marks),
      };
    },
    load: async (input, signal) => {
      if (!input.params)
        return {
          ...input.original,
          captureId: input.record.id,
          clearVersion: input.original?.clearVersion,
          total: 0,
          attention: 0,
          complete: true,
          groups: [],
        } as CaptureActivity;
      try {
        return await api<CaptureActivity>(
          `/api/captures/${input.record.id}/activity-window?${input.params}`,
          { signal },
        );
      } catch (cause) {
        if (
          !!cause &&
          typeof cause === "object" &&
          "status" in cause &&
          cause.status === 404
        )
          throw new Error("后端缺少活动统计接口（HTTP 404）");
        throw cause;
      }
    },
    publish: (result, input) => {
      if (!delta()?.total) {
        value.value = undefined;
        source.value = undefined;
        return;
      }
      if (
        result.captureId !== input.record.id ||
        result.clearVersion !== input.original?.clearVersion
      )
        return;
      value.value = visibleActivityDelta(input.pending, result);
      source.value = input.original;
    },
    reset: () => {
      value.value = undefined;
      source.value = undefined;
    },
    delay: 120,
  });
  const restartRequired = computed(
    () => !enabled() || state.error.value.includes("HTTP 404"),
  );
  const error = computed(() =>
    !enabled() ? "后端尚未提供活动统计接口" : state.error.value,
  );
  return { ...state, value, source, restartRequired, error };
}
