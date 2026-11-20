import type { IdentityState } from "./domain";
import type { CaptureActivity, EmptyReason } from "./liveReading";
export interface Connection {
  note?: string;
  identity?: ConnectionIdentity;
  identityHistory?: ConnectionIdentity[];
  identityHistoryTruncated?: boolean;
  id: string;
  endpoint: string;
  extensions: string;
  messages: number;
}
export interface ConnectionIdentity {
  state: IdentityState;
  accountId?: string;
  roleId?: string;
  nickname?: string;
  serverId?: string;
  sourceIndex: number;
  sourceProtocol: string;
}
export interface Mark {
  expired?: boolean;
  name: string;
  from: number;
  to: number;
}
export interface Capture {
  catalog?: { sha256: string; sourceFile?: string };
  operationContext?: string;
  continuous?: boolean;
  continuousError?: string;
  recoveryWarning?: string;
  diskFirstIndex?: number;
  diskLastIndex?: number;
  id: string;
  name: string;
  createdAt: string;
  messageCount: number;
  connections: Connection[];
  marks: Mark[];
  counts: Record<string, number>;
  error?: string;
  liveState?: string;
  analysisSnapshot?: boolean;
  temporary?: boolean;
  totalReceived?: number;
  clearVersion?: number;
  clearedCount?: number;
  evictedCount?: number;
  maxMessages?: number;
  firstIndex?: number;
  lastIndex?: number;
}
export interface Packet {
  fieldValues?: Record<string, string>;
  repeatOf?: number;
  connectionIdentity?: ConnectionIdentity;
  index: number;
  direction: string;
  protocolId?: number;
  protocolName?: string;
  status: string;
  ordinal: number;
  time: string;
  sequence?: number;
  flag?: number;
  wireBytes: number;
  bytes?: number;
  bodyBytes?: number;
  connection: string;
  frameId: string;
  compressed: boolean;
  preview?: string;
  body?: unknown;
  schema?: unknown;
  definition?: { line?: number; name?: string };
  packetHex?: string;
  wireHex?: string;
  inputFormat?: string;
  warnings?: string[];
  error?: string;
  catalog?: { sourceFile: string; sha256: string };
}
export interface Page {
  activity?: CaptureActivity;
  emptyReasons?: EmptyReason[];
  diagnosticTruncated?: boolean;
  queryMs?: number;
  lockWaitMs?: number;
  snapshotMs?: number;
  items: Packet[];
  total: number;
  capture: Capture;
  offset?: number;
}
/** 保留 HTTP 状态，使页面能区分旧后端缺少接口与普通网络/业务错误。 */
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}
export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  let data;
  try {
    data = await response.json();
  } catch (cause) {
    // 旧后端的未知接口可能返回纯文本；不能把 404 丢失成 JSON 解析异常。
    if (!response.ok)
      throw new ApiError(response.status, `请求失败 ${response.status}`);
    throw cause;
  }
  if (!response.ok)
    throw new ApiError(
      response.status,
      data.error || `请求失败 ${response.status}`,
    );
  return data;
}
export function post<T>(url: string, value: unknown) {
  return api<T>(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  });
}
