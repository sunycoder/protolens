export interface Connection {
  id: string;
  endpoint: string;
  extensions: string;
  messages: number;
}
export interface Mark {
  expired?: boolean;
  name: string;
  from: number;
  to: number;
}
export interface Capture {
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
  items: Packet[];
  total: number;
  capture: Capture;
}
export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  const data = await response.json();
  if (!response.ok)
    throw new Error(data.error || `请求失败 ${response.status}`);
  return data;
}
export function post<T>(url: string, value: unknown) {
  return api<T>(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  });
}
