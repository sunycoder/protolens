import type { Mark } from "./types.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
import type { ProtocolChoice } from "./protocolSelection.ts";

export const ATTENTION_STATUS = "ATTENTION";
export const MAX_WATCHED_PROTOCOLS = 24;
export interface ActivityGroup {
  token: string;
  name: string;
  protocolId?: number;
  count: number;
  attentionCount: number;
  firstIndex: number;
  lastIndex: number;
  lastAttentionIndex?: number;
}
export interface CaptureActivity {
  captureId: string;
  mode: "lifetime" | "retained";
  total: number;
  attention: number;
  lastIndex: number;
  clearVersion: number;
  firstRetained: number;
  complete: boolean;
  groups: ActivityGroup[];
}
export interface ActivityDelta {
  total: number;
  protocolCount: number;
  firstSeen: ActivityGroup[];
  attention: number;
  from: number;
  to: number;
  complete: boolean;
}
export interface EmptyReason {
  field: string;
  description: string;
  action: string;
  value: string;
  witnessIndex: number;
}

export function activityDelta(
  before: CaptureActivity | undefined,
  after: CaptureActivity | undefined,
): ActivityDelta | undefined {
  if (
    !before ||
    !after ||
    before.captureId !== after.captureId ||
    before.clearVersion !== after.clearVersion ||
    before.mode !== after.mode ||
    after.lastIndex < before.lastIndex
  )
    return;
  const old = new Map(before.groups.map((item) => [item.token, item]));
  const changed = after.groups.filter(
    (item) => item.token && item.count > (old.get(item.token)?.count || 0),
  );
  return {
    total: Math.max(0, after.total - before.total),
    protocolCount: changed.length,
    firstSeen:
      before.complete && after.complete
        ? changed.filter(
            (item) =>
              !old.has(item.token) && item.firstIndex > before.lastIndex,
          )
        : [],
    attention: Math.max(0, after.attention - before.attention),
    from: before.lastIndex + 1,
    to: after.lastIndex,
    complete: before.complete && after.complete,
  };
}

/** 仅在定义容器处解释占位；实际消息序号范围独立于标记序号。 */
export function readingRange(value: string, marks: Mark[]) {
  if (!value) return;
  if (value.startsWith("recent:")) {
    const match = /^recent:(\d+):(\d+)$/.exec(value);
    if (!match) return;
    const from = Number(match[1]),
      to = Number(match[2]);
    return Number.isSafeInteger(from) && Number.isSafeInteger(to) && to >= from
      ? { from, to, name: `消息 #${from}–${to}` }
      : undefined;
  }
  return marks[Number(value) - 1];
}

export function activityQuery(
  delta: ActivityDelta,
  category: "all" | "first" | "attention",
): Partial<WorkspaceQuery> {
  return {
    ...(category === "first"
      ? { include: delta.firstSeen.map((item) => item.token).join(",") }
      : {}),
    ...(category === "attention" ? { status: ATTENTION_STATUS } : {}),
    range: `recent:${delta.from}:${delta.to}`,
  };
}

/** 回到最新只解除通知产生的临时区间，保留用户设置的其他筛选及标记区间。 */
export function latestQuery(query: WorkspaceQuery): WorkspaceQuery {
  return {
    ...query,
    range: query.range.startsWith("recent:") ? "" : query.range,
  };
}

/** 通知忽略自身临时区间，但继续应用用户的消息筛选和标记区间。 */
export function activityWindowParams(
  delta: ActivityDelta,
  query: WorkspaceQuery,
  marks: Mark[],
) {
  const scoped = latestQuery(query);
  const range = readingRange(scoped.range, marks);
  if (scoped.range && !range) return;
  const from = Math.max(delta.from, range?.from ?? delta.from);
  const to = Math.min(delta.to, range?.to ?? delta.to);
  if (to < from) return;
  return new URLSearchParams({
    q: scoped.q,
    protocolId: scoped.protocolId,
    messageIndex: scoped.messageIndex,
    include: scoped.include,
    exclude: scoped.exclude,
    direction: scoped.direction,
    status: scoped.status,
    connection: scoped.connection,
    hideHeartbeat: String(scoped.hideHeartbeat),
    from: String(from),
    to: String(to),
  });
}

/** 区间内可查看的数量来自服务端相同筛选；本记录首次仍以累计活动的证据为准。 */
export function visibleActivityDelta(
  delta: ActivityDelta,
  window: CaptureActivity,
): ActivityDelta {
  const tokens = new Set(window.groups.map((group) => group.token));
  const complete = delta.complete && window.complete;
  return {
    ...delta,
    total: window.total,
    attention: window.attention,
    protocolCount: window.groups.filter((group) => group.token).length,
    firstSeen: complete
      ? delta.firstSeen.filter((group) => tokens.has(group.token))
      : [],
    complete,
  };
}

/** 服务端已确认有单条件解除证据；只改对应字段，排除项仅移除实际命中的 token。 */
export function relaxFilter(
  query: WorkspaceQuery,
  reason: EmptyReason,
): Partial<WorkspaceQuery> {
  if (reason.field === "hideHeartbeat") return { hideHeartbeat: false };
  if (reason.field === "exclude") {
    const remove = new Set(reason.value.split(/[,，\s]+/).filter(Boolean));
    return {
      exclude: query.exclude
        .split(/[,，\s]+/)
        .filter((token) => token && !remove.has(token))
        .join(","),
    };
  }
  const fields = [
    "range",
    "direction",
    "status",
    "connection",
    "protocolId",
    "messageIndex",
    "include",
  ];
  return fields.includes(reason.field) ? { [reason.field]: "" } : {};
}

export function watchedChoices(value: unknown): ProtocolChoice[] {
  if (!Array.isArray(value)) return [];
  const found = new Set<string>();
  return value
    .filter(
      (item): item is ProtocolChoice =>
        !!item &&
        typeof item === "object" &&
        typeof item.key === "string" &&
        item.key === item.token &&
        typeof item.label === "string" &&
        item.label.length <= 300 &&
        /^(id:\d+|name:[^,，\s]{1,200})$/.test(item.token) &&
        !found.has(item.token) &&
        !!found.add(item.token),
    )
    .slice(0, MAX_WATCHED_PROTOCOLS);
}
