import { computed, ref, watch } from "vue";
import { readStored, writeStored } from "./workspaceSession.ts";
import {
  watchedChoices,
  MAX_WATCHED_PROTOCOLS,
  type CaptureActivity,
} from "./liveReading.ts";
import { protocolChoice, type ProtocolChoice } from "./protocolSelection.ts";
import type { Capture, Packet } from "./types";

/** 关注按记录字典隔离，仅保存协议标识；累计新增数独立于列表跟随，点击查看后才确认该关注项。 */
export function useProtocolWatch(
  currentCapture: () => Capture | undefined,
  activity: () => CaptureActivity | undefined,
) {
  const selected = ref<ProtocolChoice[]>([]);
  const acknowledged = ref<Record<string, number>>({});
  const storageKey = "analyzer.protocol-watch.v1";
  const dictionaryKey = () =>
    currentCapture()?.catalog?.sha256 || currentCapture()?.id || "";
  const groups = computed(
    () => new Map(activity()?.groups.map((item) => [item.token, item]) || []),
  );
  watch(
    [() => currentCapture()?.id, dictionaryKey],
    () => {
      const stored = readStored<Record<string, unknown>>(storageKey, {});
      selected.value = watchedChoices(
        stored && typeof stored === "object"
          ? stored[dictionaryKey()]
          : undefined,
      );
      acknowledged.value = {};
    },
    { immediate: true },
  );
  watch(groups, (next) => {
    if (!activity() || activity()?.captureId !== currentCapture()?.id) return;
    for (const choice of selected.value) {
      if (acknowledged.value[choice.token] === undefined)
        acknowledged.value[choice.token] = next.get(choice.token)?.count || 0;
    }
  });
  watch(
    [() => activity()?.mode, () => activity()?.clearVersion],
    (next, previous) => {
      if (
        next[0] &&
        previous[0] &&
        (next[0] !== previous[0] || next[1] !== previous[1])
      ) {
        acknowledged.value = Object.fromEntries(
          [...groups.value].map(([token, group]) => [token, group.count]),
        );
      }
    },
  );
  const choices = computed(() =>
    selected.value.map((choice) => ({
      ...choice,
      unread: Math.max(
        0,
        (groups.value.get(choice.token)?.count || 0) -
          (acknowledged.value[choice.token] || 0),
      ),
    })),
  );
  function has(row: Packet) {
    const choice = protocolChoice(row);
    return (
      !!choice && selected.value.some((item) => item.token === choice.token)
    );
  }
  function save() {
    const value = readStored<Record<string, unknown>>(storageKey, {});
    const previous =
      value && typeof value === "object" && !Array.isArray(value) ? value : {};
    const next = { ...previous, [dictionaryKey()]: selected.value };
    const entries = Object.entries(next);
    writeStored(storageKey, Object.fromEntries(entries.slice(-8)));
  }
  function toggle(row: Packet) {
    const choice = protocolChoice(row);
    if (!choice || !watchedChoices([choice]).length)
      return "此消息没有可精确关注的协议标识";
    if (has(row)) {
      selected.value = selected.value.filter(
        (item) => item.token !== choice.token,
      );
      save();
      return "";
    }
    if (selected.value.length >= MAX_WATCHED_PROTOCOLS)
      return `最多关注 ${MAX_WATCHED_PROTOCOLS} 种协议，请先取消不用的关注`;
    selected.value = [...selected.value, choice];
    acknowledged.value[choice.token] =
      groups.value.get(choice.token)?.count || 0;
    save();
    return "";
  }
  function remove(token: string) {
    selected.value = selected.value.filter((item) => item.token !== token);
    save();
  }
  function acknowledge(token: string, count: number) {
    acknowledged.value[token] = Math.max(acknowledged.value[token] || 0, count);
  }
  return {
    choices,
    has,
    toggle,
    remove,
    acknowledge,
    count: (token: string) => groups.value.get(token)?.count || 0,
  };
}
