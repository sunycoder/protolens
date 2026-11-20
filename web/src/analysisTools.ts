import type { Capture, Packet } from "./types.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
import { IdentityState } from "./domain.ts";
import { latestQuery, readingRange } from "./liveReading.ts";

export const MAX_FIELD_COLUMNS = 6;
export const MAX_ANALYSIS_TABS = 5;
export type ValueFormat = "raw" | "hex" | "bytes" | "unixms" | "unixs";
export interface FieldColumn {
  path: string;
  label: string;
}
export interface MessageNote {
  index: number;
  path: string;
  text: string;
  kind: "备注" | "推测" | "已验证";
}
export interface AnalysisGroup {
  key: string;
  protocolId?: number;
  protocolName: string;
  direction: string;
  status: string;
  count: number;
  firstIndex: number;
  lastIndex: number;
  time: string;
}
export interface RangeAnalysis {
  captureId: string;
  dictionary: string;
  from: number;
  to: number;
  total: number;
  missingBefore: boolean;
  truncated: boolean;
  timelineTruncated: boolean;
  groups: AnalysisGroup[];
  anomalies: AnalysisGroup[];
  fields: {
    path: string;
    first: string;
    last: string;
    changed: boolean;
    changes: number;
    lastIndex: number;
    signature: string;
  }[];
  timeline: {
    index: number;
    protocolName: string;
    protocolId?: number;
    direction: string;
    time: string;
    connection: string;
  }[];
}

/** 所有分析显式继承列表筛选；调用方可指定原编号区间，不把临时通知区间当作永久筛选。 */
export function analysisParams(
  query: WorkspaceQuery,
  capture: Capture,
  fields: FieldColumn[],
  range?: { from: number; to: number },
) {
  const shown = query;
  const mark = readingRange(shown.range, capture.marks);
  if (!range && shown.range && !mark)
    throw new Error("当前区间已不可用，请先取消区间限制");
  const scope = range || mark;
  return new URLSearchParams({
    q: shown.q,
    protocolId: shown.protocolId,
    messageIndex: shown.messageIndex,
    include: shown.include,
    exclude: shown.exclude,
    direction: shown.direction,
    status: shown.status,
    connection: shown.connection,
    hideHeartbeat: String(shown.hideHeartbeat),
    from: String(scope?.from ?? capture.firstIndex ?? 0),
    to: String(scope?.to ?? capture.lastIndex ?? 2147483647),
    fields: JSON.stringify(fields.map((field) => field.path)),
  });
}

/** 转换由用户选择，不猜测单位；保留原值，超出安全整数的数值拒绝二次换算。 */
export function formatScalar(
  value: unknown,
  format: ValueFormat,
): string | undefined {
  if (format === "raw") return;
  if (typeof value === "number" && !Number.isSafeInteger(value))
    return "原数值不在安全整数范围，无法可靠转换";
  const text = String(value);
  if (!/^-?\d{1,40}$/.test(text)) return "此格式需要整数或整数字符串";
  const number = BigInt(text);
  if (format === "hex")
    return `${number < 0n ? "-" : ""}0x${(number < 0n ? -number : number).toString(16).toUpperCase()}`;
  if (format === "bytes") {
    if (number < 0n || number > BigInt(Number.MAX_SAFE_INTEGER))
      return "字节数超出可换算范围";
    const units = ["B", "KiB", "MiB", "GiB", "TiB"];
    let amount = Number(number),
      unit = 0;
    while (amount >= 1024 && unit < units.length - 1) {
      amount /= 1024;
      unit++;
    }
    return `${amount.toFixed(unit ? 2 : 0)} ${units[unit]}`;
  }
  const millis = format === "unixs" ? number * 1000n : number;
  if (millis < -8640000000000000n || millis > 8640000000000000n)
    return "时间戳超出可显示范围";
  return `${new Date(Number(millis)).toISOString()}（UTC）`;
}

/** 本页折叠仅接受后端证明相同的连续组，展开不修改分页计数或原消息。 */
export type MessageGrouping = "none" | "content" | "protocol";

/** 本页同协议归组允许内容不同与协议穿插，但始终隔离连接、方向及解码状态，不代表消息连续或相同。 */
export function groupedMessageRows(
  rows: Packet[],
  mode: MessageGrouping,
): Packet[] {
  if (mode !== "protocol") return rows;
  const first = new Map<string, number>();
  return rows.map((row) => {
    const { repeatOf, ...original } = row;
    if (row.protocolId === undefined && !row.protocolName) return original;
    const key = JSON.stringify([
      row.connection,
      row.direction,
      row.protocolId,
      row.protocolName,
      row.status,
    ]);
    const start = first.get(key);
    if (start !== undefined) return { ...original, repeatOf: start };
    first.set(key, row.index);
    return original;
  });
}

export function foldRows(
  rows: Packet[],
  enabled: boolean,
  expanded: Set<number>,
) {
  if (!enabled) return rows;
  return rows.filter(
    (row) => row.repeatOf === undefined || expanded.has(row.repeatOf),
  );
}
export function repeatGroups(rows: Packet[]) {
  const result = new Map<number, Packet[]>();
  for (const row of rows) {
    const key = row.repeatOf ?? row.index;
    const group = result.get(key) || [];
    group.push(row);
    result.set(key, group);
  }
  return result;
}

/** 退出临时视图仅恢复其改写且用户未再次修改的字段。 */
export function exitTemporaryQuery(
  current: WorkspaceQuery,
  origin?: WorkspaceQuery,
  applied?: WorkspaceQuery,
): WorkspaceQuery {
  const result = { ...current };
  if (origin && applied)
    for (const key of Object.keys(result) as (keyof WorkspaceQuery)[]) {
      if (result[key] === applied[key])
        Object.assign(result, { [key]: origin[key] });
    }
  return latestQuery(result);
}

export function recordGroups(captures: Capture[]) {
  const groups = new Map<
    string,
    { key: string; label: string; items: Capture[] }
  >();
  for (const capture of captures) {
    const identity =
      capture.connections?.length === 1
        ? capture.connections[0].identity
        : undefined;
    let origin = "";
    try {
      origin = new URL(capture.connections?.[0]?.endpoint || "").origin;
    } catch {}
    const confirmed =
      identity?.state === IdentityState.IDENTIFIED &&
      identity.roleId &&
      identity.serverId &&
      capture.catalog?.sha256 &&
      origin;
    const key = confirmed
      ? JSON.stringify([
          capture.catalog?.sha256 || "",
          origin,
          identity.serverId,
          identity.roleId,
        ])
      : capture.id;
    const group = groups.get(key) || {
      key,
      label: confirmed
        ? `${identity.nickname || "角色"} · ${identity.serverId}服 · ${identity.roleId}`
        : capture.name,
      items: [],
    };
    group.items.push(capture);
    groups.set(key, group);
  }
  return [...groups.values()];
}

export function compareOperations(before: RangeAnalysis, after: RangeAnalysis) {
  const left = new Map(before.groups.map((group) => [group.key, group]));
  const right = new Map(after.groups.map((group) => [group.key, group]));
  return [...new Set([...left.keys(), ...right.keys()])].map((key) => ({
    key,
    name: (left.get(key) || right.get(key))!.protocolName,
    direction: (left.get(key) || right.get(key))!.direction,
    before: left.get(key)?.count || 0,
    after: right.get(key)?.count || 0,
    firstBefore: left.get(key)?.firstIndex,
    firstAfter: right.get(key)?.firstIndex,
  }));
}
