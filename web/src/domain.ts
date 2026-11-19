/** 后端的稳定枚举值。保留原始字符串合同，供页面、筛选和身份展示共同使用。 */
export const DecodeStatus = {
  DECODED: "DECODED",
  PARTIAL: "PARTIAL",
  UNMAPPED: "UNMAPPED",
  FAILED: "FAILED",
} as const;
export type DecodeStatus = (typeof DecodeStatus)[keyof typeof DecodeStatus];

export const MessageDirection = { CS: "CS", SC: "SC" } as const;
export type MessageDirection =
  (typeof MessageDirection)[keyof typeof MessageDirection];

export const CollectorState = {
  NOT_STARTED: "NOT_STARTED",
  DISABLED: "DISABLED",
  PREPARING: "PREPARING",
  EXTERNAL: "EXTERNAL",
  MISSING: "MISSING",
  RUNNING: "RUNNING",
  FAILED: "FAILED",
  STOPPED: "STOPPED",
} as const;
export type CollectorState =
  (typeof CollectorState)[keyof typeof CollectorState];

export const IdentityState = {
  PENDING: "pending",
  IDENTIFIED: "identified",
} as const;
export type IdentityState = (typeof IdentityState)[keyof typeof IdentityState];

export const OperatingSystem = {
  WINDOWS: "windows",
  MACOS: "macos",
  LINUX: "linux",
} as const;
export type OperatingSystem =
  (typeof OperatingSystem)[keyof typeof OperatingSystem];
