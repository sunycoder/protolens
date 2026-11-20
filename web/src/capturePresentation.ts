import { DecodeStatus } from "./domain.ts";
import { ATTENTION_STATUS } from "./liveReading.ts";
import type { Capture } from "./types.ts";
import { connectionLabel } from "./connectionIdentity.ts";
export function captureName(item: Capture) {
  if (
    item.connections.length === 1 &&
    (item.connections[0].note || item.connections[0].identity)
  ) {
    return connectionLabel(item.connections[0]);
  }
  return item.name.startsWith("实时 · ")
    ? "实时 · " + (item.connections[0]?.id.slice(0, 8) || item.id.slice(0, 8))
    : item.name;
}

export function messageTime(value: string) {
  if (!value) return "未知";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString("zh-CN", {
        hour12: false,
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        fractionalSecondDigits: 3,
      });
}

export const statusText: Record<string, string> = {
  [DecodeStatus.DECODED]: "已解码",
  [DecodeStatus.PARTIAL]: "部分解码",
  [DecodeStatus.FAILED]: "解码失败",
  [DecodeStatus.UNMAPPED]: "未映射",
  [ATTENTION_STATUS]: "需关注（部分 / 失败 / 未映射）",
};
