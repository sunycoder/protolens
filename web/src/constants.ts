/** 页面查询与资源边界。端口等运行配置仍以服务端响应为准，此处不添加替代默认端口。 */
export const DEFAULT_PAGE_SIZE = 100;
export const MAX_UPLOAD_BYTES = 128 * 1024 * 1024;
export const MAX_PORT = 65535;
export const LOOPBACK_HOST = "127.0.0.1";
export const MAX_CONNECTION_NOTE_LENGTH = 40;
export const MAX_IDENTITY_HISTORY = 256;
export const CACHE_BUDGET = {
  defaultMessages: 10000,
  minMessages: 100,
  maxMessages: 50000,
  defaultMiB: 64,
  minMiB: 1,
  maxMiB: 256,
} as const;

/** 搜索和对比使用不同总节点预算；不能因数值接近就混为同一个上限。 */
export const FIELD_SEARCH_LIMITS = {
  nodes: 10000,
  matches: 50,
  depth: 32,
} as const;
export const FIELD_COMPARE_LIMITS = {
  nodes: 20000,
  changes: 500,
  depth: 32,
  warnings: 10,
} as const;
export const FIELD_PREVIEW_LENGTH = 500;
export const TEXT_PREVIEW_CHUNK = 32768;
export const JSON_CHILD_PAGE_SIZE = 100;
export const SCALAR_PREVIEW_LENGTH = 240;
export const SCALAR_EXPANSION_CHUNK = 4096;
export const MESSAGE_REQUEST_TIMEOUT_MS = 30000;
export const DETAIL_REQUEST_TIMEOUT_MS = 15000;
export const DETAIL_PREFETCH_MAX_BYTES = 128 * 1024;
export const LIVE_REFRESH_DEBOUNCE_MS = 1000;
export const PROXY_NETWORK_CHECK_INTERVAL_MS = 30000;

export const MAX_CAPTURE_NAME_LENGTH = 100;
export const MAX_OPERATION_CONTEXT_LENGTH = 2000;
export const MAX_PROTOCOL_OVERVIEW_GROUPS = 200;
