import { computed, ref, shallowRef, watch, onScopeDispose } from "vue";
import { api, type Capture } from "./types.ts";
import { RequestGate } from "./workspaceRefresh.ts";
import {
  activityDelta,
  type CaptureActivity,
  type ActivityDelta,
} from "./liveReading.ts";

/** 活动快照与列表已读基线分开；迟到的列表快照只确认它实际包含的消息，不吞掉随后到达的消息。 */
export function useCaptureActivity(currentCapture: () => Capture | undefined) {
  const latest = shallowRef<CaptureActivity>();
  const baseline = shallowRef<CaptureActivity>();
  const recent = shallowRef<ActivityDelta>();
  const error = ref("");
  const gate = new RequestGate();
  watch(
    () => currentCapture()?.id,
    () => {
      gate.cancel();
      latest.value = baseline.value = undefined;
      recent.value = undefined;
      error.value = "";
    },
  );
  function publish(snapshot: CaptureActivity | undefined, acknowledge = false) {
    if (!snapshot || snapshot.captureId !== currentCapture()?.id) return;
    const previous = latest.value;
    if (
      previous &&
      (snapshot.clearVersion < previous.clearVersion ||
        (previous.mode === "retained" && snapshot.mode === "lifetime"))
    )
      return;
    const changedScope =
      !!previous &&
      (snapshot.mode !== previous.mode ||
        snapshot.clearVersion !== previous.clearVersion);
    if (!previous || changedScope || snapshot.lastIndex >= previous.lastIndex) {
      const delta = activityDelta(previous, snapshot);
      if (changedScope) recent.value = undefined;
      else if (delta?.total) recent.value = delta;
      latest.value = snapshot;
    }
    if (
      !baseline.value ||
      changedScope ||
      (acknowledge && snapshot.lastIndex >= baseline.value.lastIndex)
    )
      baseline.value = snapshot;
  }
  async function refresh() {
    const id = currentCapture()?.id;
    if (!id) return;
    const request = gate.begin();
    try {
      const snapshot = await api<CaptureActivity>(
        `/api/captures/${id}/activity`,
        { signal: request.signal },
      );
      if (request.current()) {
        error.value = "";
        publish(snapshot);
      }
    } catch (cause) {
      if (request.current()) error.value = String(cause);
    }
  }
  const unread = computed(() => activityDelta(baseline.value, latest.value));
  onScopeDispose(() => gate.cancel());
  return {
    latest,
    baseline,
    recent,
    unread,
    error,
    publish,
    refresh,
    acknowledge: (value = latest.value) => {
      if (
        value &&
        (!baseline.value || value.lastIndex >= baseline.value.lastIndex)
      )
        baseline.value = value;
    },
  };
}
