/** 查询结果、布局和快捷键的独立规则，便于验证失败时序及真实窗口边界。 */
export interface WorkspaceQuery {
  q: string;
  protocolId: string;
  messageIndex: string;
  include: string;
  exclude: string;
  direction: string;
  status: string;
  connection: string;
  hideHeartbeat: boolean;
  order: string;
  range: string;
}
export const defaultQuery: WorkspaceQuery = {
  q: "",
  protocolId: "",
  messageIndex: "",
  include: "",
  exclude: "",
  direction: "",
  status: "",
  connection: "",
  hideHeartbeat: true,
  order: "desc",
  range: "",
};
export type DetailLayout = "auto" | "right" | "bottom" | "overlay";
export interface TableColumns {
  time: boolean;
  protocolId: boolean;
  status: boolean;
  bytes: boolean;
  sequence: boolean;
  connection: boolean;
  actions: boolean;
}
export const defaultColumns: TableColumns = {
  time: true,
  protocolId: true,
  status: true,
  bytes: false,
  sequence: false,
  connection: false,
  actions: true,
};
export function listMinimumWidth(columns: TableColumns) {
  return (
    20 +
    82 +
    230 +
    220 +
    (columns.time ? 108 : 0) +
    58 +
    (columns.protocolId ? 90 : 0) +
    (columns.status ? 92 : 0) +
    (columns.bytes ? 76 : 0) +
    (columns.sequence ? 76 : 0) +
    (columns.connection ? 112 : 0) +
    (columns.actions ? 50 : 0)
  );
}
export function chooseDetailLayout(
  width: number,
  height: number,
  preference: DetailLayout,
  listWidth: number,
): Exclude<DetailLayout, "auto"> {
  const fitsRight = width >= listWidth + 327;
  if (preference === "overlay") return "overlay";
  if (preference === "right") return fitsRight ? "right" : "overlay";
  if (preference === "bottom") return height >= 560 ? "bottom" : "overlay";
  if (fitsRight) return "right";
  return height >= 650 ? "bottom" : "overlay";
}
export function readColumns(serialized: string | null): TableColumns {
  const result = { ...defaultColumns };
  try {
    const value = JSON.parse(serialized || "{}");
    for (const key of Object.keys(result) as (keyof TableColumns)[])
      if (typeof value?.[key] === "boolean") result[key] = value[key];
  } catch {
    /* 损坏的本机偏好不影响进入工作台。 */
  }
  return result;
}
export function contextQuery(
  query: WorkspaceQuery,
  connection: string,
): WorkspaceQuery {
  return {
    ...defaultQuery,
    order: query.order,
    hideHeartbeat: query.hideHeartbeat,
    connection,
  };
}
export function contextRankParams(query: WorkspaceQuery, index: number) {
  const params = new URLSearchParams({
    order: query.order,
    connection: query.connection,
    hideHeartbeat: String(query.hideHeartbeat),
    limit: "1",
  });
  params.set(query.order === "desc" ? "from" : "to", String(index));
  return params;
}
export function allowReadingShortcut(
  key: string,
  editing: boolean,
  modal: boolean,
  modified: boolean,
) {
  return (
    !editing &&
    !modal &&
    !modified &&
    ["ArrowUp", "ArrowDown", "Escape"].includes(key)
  );
}

/** 只有最新的成功请求可以发布结果，取消、失败和迟到响应均保留之前的展示。 */
export class ResultPublication<T> {
  private version = 0;
  private shown?: T;
  begin() {
    return ++this.version;
  }
  cancel() {
    this.version++;
  }
  commit(version: number, value: T) {
    if (version !== this.version) return false;
    this.shown = value;
    return true;
  }
  get value() {
    return this.shown;
  }
}
