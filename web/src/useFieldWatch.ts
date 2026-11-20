import { onScopeDispose, ref, watch } from "vue";
import { api, type Capture, type Packet } from "./types.ts";
import { RequestGate } from "./workspaceRefresh.ts";
import type { FieldColumn, RangeAnalysis } from "./analysisTools.ts";

interface WatchedField extends FieldColumn {
  key: string;
  protocolId?: number;
  protocolName: string;
  connection: string;
  direction: string;
  cursor: number;
  first?: string;
  last?: string;
  signature?: string;
  index: number;
  changed: boolean;
  incomplete: boolean;
  error: string;
}
/** 字段关注只存在本次记录，最多 3 个；同一消息流合并请求，不查询其他页签或整个消息正文。 */
export function useFieldWatch(
  capture: () => Capture | undefined,
  lastIndex: () => number | undefined,
  enabled: () => boolean,
) {
  const items = ref<WatchedField[]>([]),
    busy = ref(false);
  const gate = new RequestGate();
  let timer: ReturnType<typeof setTimeout> | undefined;
  function add(field: FieldColumn, packet?: Packet) {
    if (!packet) throw new Error("请先选中包含该字段的消息");
    let value: unknown = packet.body;
    for (const token of field.path
      .slice(1)
      .split("/")
      .map((key) => key.replace(/~1/g, "/").replace(/~0/g, "~"))) {
      if (
        value === null ||
        typeof value !== "object" ||
        !Object.hasOwn(value, token)
      )
        throw new Error("当前消息不存在该字段");
      value = (value as Record<string, unknown>)[token];
    }
    if (
      (value !== null && typeof value === "object") ||
      String(value).length > 4096
    )
      throw new Error("字段关注仅支持长度不超过 4096 字符的标量值");
    const key = JSON.stringify([
      packet.protocolId,
      packet.protocolName,
      packet.connection,
      packet.direction,
      field.path,
    ]);
    if (items.value.some((item) => item.key === key)) return;
    if (items.value.length >= 3)
      throw new Error("最多同时关注 3 个字段，请先移除不需要的关注");
    items.value.push({
      ...field,
      key,
      protocolId: packet.protocolId,
      protocolName: packet.protocolName || "",
      connection: packet.connection,
      direction: packet.direction,
      cursor: packet.index,
      index: packet.index,
      changed: false,
      incomplete: false,
      error: "",
    });
    schedule();
  }
  function schedule() {
    if (timer) clearTimeout(timer);
    gate.cancel();
    busy.value = false;
    if (!enabled() || !capture() || !items.value.length) return;
    timer = setTimeout(refresh, 250);
  }
  async function refresh() {
    const record = capture(),
      to = lastIndex();
    if (!record || to === undefined || !enabled()) return;
    const request = gate.begin();
    busy.value = true;
    const groups = new Map<string, WatchedField[]>();
    for (const item of items.value) {
      if (item.cursor > to) continue;
      const key = JSON.stringify([
        item.protocolId,
        item.protocolName,
        item.connection,
        item.direction,
      ]);
      const group = groups.get(key) || [];
      group.push({ ...item });
      groups.set(key, group);
    }
    await Promise.all(
      [...groups.values()].map(async (group) => {
        const first = group[0];
        const params = new URLSearchParams({
          from: String(Math.min(...group.map((item) => item.cursor))),
          to: String(to),
          protocolId:
            first.protocolId === undefined ? "" : String(first.protocolId),
          include:
            first.protocolId === undefined ? `name:${first.protocolName}` : "",
          connection: first.connection,
          direction: first.direction,
          hideHeartbeat: "false",
          fields: JSON.stringify(group.map((item) => item.path)),
        });
        try {
          const result = await api<RangeAnalysis>(
            `/api/captures/${record.id}/analysis?${params}`,
            { signal: request.signal },
          );
          if (!request.current() || capture()?.id !== record.id) return;
          for (const watched of group) {
            const current = items.value.find(
                (item) => item.key === watched.key,
              ),
              field = result.fields.find((item) => item.path === watched.path);
            if (!current) continue;
            current.incomplete = result.missingBefore || result.truncated;
            if (field && field.lastIndex >= 0) {
              if (!field.signature) {
                current.error = "字段值过大或已变为容器，无法可靠监视";
                continue;
              }
              const changed = watched.signature
                ? watched.signature !== field.signature || field.changes > 0
                : field.changed || field.changes > 0;
              if (changed) {
                current.first = current.last ?? field.first;
                current.changed = true;
              }
              current.last = field.last;
              current.signature = field.signature;
              current.index = field.lastIndex;
              current.error = "";
            }
            if (!result.truncated) current.cursor = to;
          }
        } catch (cause) {
          if (request.current())
            for (const watched of group) {
              const current = items.value.find(
                (item) => item.key === watched.key,
              );
              if (current) current.error = String(cause);
            }
        }
      }),
    );
    if (request.current()) busy.value = false;
  }
  watch(
    () => capture()?.id,
    () => {
      items.value = [];
      schedule();
    },
  );
  watch([lastIndex, enabled], schedule);
  onScopeDispose(() => {
    if (timer) clearTimeout(timer);
    gate.cancel();
  });
  return {
    items,
    busy,
    add,
    refresh,
    remove: (key: string) => {
      items.value = items.value.filter((item) => item.key !== key);
      schedule();
    },
  };
}
