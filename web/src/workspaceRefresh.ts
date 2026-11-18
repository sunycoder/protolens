import type { Packet } from "./types.ts";

/** 实时通知只决定哪些记录有新数据，不能替用户切换正在查看的记录。 */
export interface CaptureProgress {
  captureId: string;
  messages: number;
  retainedMessages?: number;
  clearVersion?: number;
  clearedCount?: number;
  evictedCount?: number;
  frames: number;
  failed: number;
  connection: string;
  endpoint: string;
}
export function changedCaptures(
  previous: CaptureProgress[],
  incoming: CaptureProgress[],
): string[] {
  const old = new Map(
    previous.map((item) => [
      item.captureId,
      `${item.messages}:${item.clearVersion || 0}`,
    ]),
  );
  const next = new Map(
    incoming.map((item) => [
      item.captureId,
      `${item.messages}:${item.clearVersion || 0}`,
    ]),
  );
  return [...new Set([...old.keys(), ...next.keys()])].filter(
    (id) => old.get(id) !== next.get(id),
  );
}
/** 跟随当前记录时不先产生再消除新增提示，避免实时刷新使工具栏闪动或移位。 */
export function shouldCountUnread(
  currentId: string | undefined,
  incomingId: string,
  follow: boolean,
  browsingHistory: boolean,
) {
  return currentId !== incomingId || !follow || browsingHistory;
}
export function shouldRefreshMessages(
  currentId: string | undefined,
  changed: string[],
  follow: boolean,
  browsingHistory: boolean,
) {
  return (
    !!currentId && follow && !browsingHistory && changed.includes(currentId)
  );
}

/** 同一记录的消息内容不可变；保留相同摘要的对象引用，避免表格刷新重建选中行。 */
export function reconcileRows(
  previous: Packet[],
  incoming: Packet[],
): Packet[] {
  const old = new Map(previous.map((item) => [item.index, item]));
  const next = incoming.map((item) => {
    const existing = old.get(item.index);
    return existing &&
      existing.connection === item.connection &&
      existing.time === item.time &&
      existing.status === item.status &&
      existing.protocolId === item.protocolId
      ? existing
      : item;
  });
  return previous.length === next.length &&
    previous.every((item, index) => item === next[index])
    ? previous
    : next;
}

/** 手动导航使旧请求失效；AbortController 同时减少浏览器无效传输，代次检查防止迟到结果覆盖新选择。 */
export class RequestGate {
  private generation = 0;
  private controller?: AbortController;
  begin() {
    this.cancel();
    const generation = this.generation;
    this.controller = new AbortController();
    return {
      signal: this.controller.signal,
      current: () => generation === this.generation,
    };
  }
  cancel() {
    this.controller?.abort();
    this.generation++;
  }
}
