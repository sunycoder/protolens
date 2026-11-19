import type { WorkspaceQuery, TableColumns } from "./workspaceInteractions";

/** 只保存分析导航，不保存消息体、凭证或文件内容；存储受限时仍可正常使用工作台。 */
export interface ReadingPlace {
  captureId: string;
  query: WorkspaceQuery;
  page: number;
  scrollTop: number;
  selectedIndex?: number;
  detailOpen: boolean;
  tab: string;
}
export interface SavedView {
  name: string;
  query: WorkspaceQuery;
  columns: TableColumns;
  dictionary: string;
}
export function readStored<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) || "null") ?? fallback;
  } catch {
    return fallback;
  }
}
export function writeStored(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}
export function validQuery(value: unknown): value is WorkspaceQuery {
  if (!value || typeof value !== "object") return false;
  const query = value as Record<string, unknown>;
  return (
    [
      "q",
      "protocolId",
      "messageIndex",
      "include",
      "exclude",
      "direction",
      "status",
      "connection",
      "order",
      "range",
    ].every(
      (key) =>
        typeof query[key] === "string" &&
        (query[key] as string).length <= 10000,
    ) &&
    typeof query.hideHeartbeat === "boolean" &&
    ["asc", "desc"].includes(String(query.order))
  );
}
export function validPlace(
  value: ReadingPlace | undefined,
): value is ReadingPlace {
  return (
    !!value &&
    typeof value.captureId === "string" &&
    validQuery(value.query) &&
    Number.isInteger(value.page) &&
    value.page > 0 &&
    Number.isFinite(value.scrollTop) &&
    (value.selectedIndex === undefined ||
      (Number.isInteger(value.selectedIndex) && value.selectedIndex >= 0))
  );
}
/** 快捷操作只修改当前结果条件，不把输入框草稿顺带提交。 */
export function patchQuery(
  shown: WorkspaceQuery,
  patch: Partial<WorkspaceQuery>,
): WorkspaceQuery {
  return { ...shown, ...patch };
}
