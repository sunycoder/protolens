/** 协议查询的只读合同；版本与字典范围必须和每次详情请求一起传递。 */
export const PROTOCOL_QUERY_PAGE_SIZE = 50;
export interface CatalogIdentity {
  version: string;
  sha256: string;
  sourceFile: string;
  sourceRoot: string;
  messageCount: number;
  definitionCount: number;
}
export interface ProtocolQueryEntry {
  key: string;
  name: string;
  kind: "message" | "structure";
  protocolId?: number;
  direction: string;
  fieldCount: number;
  matchReason?: string;
  matchPath?: string;
  path?: string;
}
export interface ProtocolField {
  id: string;
  name: string;
  type: string;
  path: string;
  structures: { key: string; name: string; pathSuffix: string }[];
}
export interface ProtocolDefinition extends ProtocolQueryEntry {
  linkedCaptureId?: string;
  linkNotice?: string;
  catalog: CatalogIdentity;
  captureId: string;
  sourceLine: number;
  fields: ProtocolField[];
  fieldOffset: number;
  fieldPageSize: number;
  fieldsHaveMore: boolean;
  rawDefinition: Record<string, unknown>;
  references: ProtocolQueryEntry[];
  referenceCount: number;
  usedByProtocols: ProtocolQueryEntry[];
  usedByProtocolCount: number;
  occurrences?: {
    count: number;
    firstIndex: number;
    lastIndex: number;
    scope: string;
  };
}
export interface ProtocolSearchPage {
  items: ProtocolQueryEntry[];
  total: number;
  offset: number;
  limit: number;
  catalog: CatalogIdentity;
  captureId: string;
}
export interface ProtocolQueryContext {
  captureId: string;
  version: string;
  contextCaptureId?: string;
}
export interface ProtocolQueryTarget {
  key?: string;
  captureId: string;
  fieldPath?: string;
  sequence: number;
}

export function definitionUrl(
  context: ProtocolQueryContext,
  key: string,
  fieldOffset = 0,
  matchPath = "",
) {
  const params = new URLSearchParams({
    captureId: context.captureId,
    version: context.version,
    key,
    matchPath,
    fieldOffset: String(fieldOffset),
  });
  if (context.contextCaptureId)
    params.set("contextCaptureId", context.contextCaptureId);
  return `/api/protocol-query/definition?${params}`;
}

/** 对齐定义路径与实际消息路径；只在容器标记处解释数组下标和解码器的 Map 条目结构。 */
export function definitionPathRemainder(
  pattern: string,
  path: string,
): string | undefined {
  const expected = pattern.split("/").slice(1),
    actual = path.split("/").slice(1);
  let index = 0;
  for (const part of expected) {
    if (part === actual[index]) {
      index++;
      continue;
    }
    if (part === "[]" && /^(0|[1-9]\d*)$/.test(actual[index] || "")) {
      index++;
      continue;
    }
    if (
      (part === "{key}" || part === "{value}") &&
      actual[index] === "$map" &&
      /^(0|[1-9]\d*)$/.test(actual[index + 1] || "") &&
      actual[index + 2] === part.slice(1, -1)
    ) {
      index += 3;
      continue;
    }
    return undefined;
  }
  return index === actual.length ? "" : "/" + actual.slice(index).join("/");
}
/** 路径使用 JSON Pointer 转义，容器标记保持为定义路径，不冒充具体消息中的数组下标。 */
export function fieldPathLabel(path: string) {
  return path
    .split("/")
    .slice(1)
    .map((part) => part.replace(/~1/g, "/").replace(/~0/g, "~"))
    .join(".");
}
/** 只对同一记录的当前保留范围应用精确协议筛选，不保留其他条件造成“有消息却查不到”。 */
export function protocolMessageQuery(protocolId: number) {
  return {
    q: "",
    protocolId: String(protocolId),
    messageIndex: "",
    include: "",
    exclude: "",
    direction: "",
    status: "",
    connection: "",
    range: "",
    hideHeartbeat: false,
  };
}
