import type { Ref, ShallowRef } from "vue";
import { api, type Capture } from "./types.ts";
import {
  shouldCountUnread,
  shouldRefreshMessages,
  type CaptureProgress,
} from "./workspaceRefresh.ts";
import type { useMessageLoader } from "./useMessageLoader.ts";
import type { useCaptureActivity } from "./useCaptureActivity.ts";
/** 实时事件更新摘要与未读数，只有当前记录跟随时刷新消息；其他记录事件不能抢走阅读位置。 */
export function useCaptureFeed(options: {
  capture: ShallowRef<Capture | undefined>;
  captures: ShallowRef<Capture[]>;
  unread: ShallowRef<Record<string, number>>;
  autoFollow: Ref<boolean>;
  browsingHistory: Ref<boolean>;
  loader: ReturnType<typeof useMessageLoader>;
  activity: ReturnType<typeof useCaptureActivity>;
  reloadCaptures: () => Promise<void>;
  openCapture: (capture: Capture) => Promise<void>;
  failure: (error: unknown) => void;
}) {
  const {
    capture,
    captures,
    unread,
    autoFollow,
    browsingHistory,
    activity,
    reloadCaptures,
    openCapture,
    failure,
  } = options;
  const { loading, loadMessages } = options.loader;
  const messageLoader = options.loader;
  let listReloading = false;
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
            !known.has(id) ||
            !connections.some((item) => item.captureId === id),
        )
      ) {
        listReloading = true;
        try {
          await reloadCaptures();
        } finally {
          listReloading = false;
        }
      }
      const progress = new Map(
        connections.map((item) => [item.captureId, item]),
      );
      const nextUnread = { ...unread.value };
      captures.value = captures.value.map((item) => {
        const current = progress.get(item.id);
        if (
          !current ||
          (current.messages === (item.totalReceived ?? item.messageCount) &&
            (current.clearVersion || 0) === (item.clearVersion || 0) &&
            JSON.stringify(current.connectionInfo || {}) ===
              JSON.stringify(item.connections[0] || {}))
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
          connections: current.connectionInfo
            ? [current.connectionInfo]
            : item.connections,
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
      const activeSummary = captures.value.find(
        (item) => item.id === capture.value?.id,
      );
      if (activeSummary && capture.value) {
        capture.value = {
          ...capture.value,
          connections: activeSummary.connections,
        };
      }
      const activeId = capture.value?.id;
      if (
        activeId &&
        changedIds.includes(activeId) &&
        (!autoFollow.value || browsingHistory.value)
      )
        void activity.refresh();
      if (
        activeId &&
        capture.value?.diskFirstIndex !== undefined &&
        changedIds.includes(activeId) &&
        !autoFollow.value
      ) {
        const metadata = await api<Capture>(
          `/api/captures/${activeId}/overview`,
        );
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
        if (loading.value) messageLoader.queueLive();
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

  return { refreshLive, openLiveCapture };
}
