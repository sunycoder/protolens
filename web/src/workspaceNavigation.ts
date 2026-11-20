import type { ReadingPlace } from "./workspaceSession.ts";
export interface ReadingCheckpoint {
  place: ReadingPlace;
  following: boolean;
  field: string;
}
/** 工作区位置只保存元数据；不长期复制消息体，证据淘汰后由恢复入口明确提示。 */
export function validWidth(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isFinite(value) &&
    value >= 50 &&
    value <= 800
  );
}
export function pointerTrail(path: string) {
  const trail = [{ path: "", label: "消息体" }];
  let parent = "";
  for (const token of path.split("/").slice(1)) {
    parent += "/" + token;
    trail.push({
      path: parent,
      label: token.replace(/~1/g, "/").replace(/~0/g, "~"),
    });
  }
  return trail;
}
export function normalizedSearches(
  value: unknown,
): { q: string; pinned: boolean }[] {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  return value
    .filter(
      (item): item is { q: string; pinned: boolean } =>
        !!item &&
        typeof item.q === "string" &&
        !!item.q.trim() &&
        item.q.length <= 300 &&
        !seen.has(item.q) &&
        !!seen.add(item.q),
    )
    .slice(0, 20)
    .map((item) => ({ q: item.q, pinned: item.pinned === true }));
}
