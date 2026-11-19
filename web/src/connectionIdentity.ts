import { IdentityState } from "./domain.ts";
import type { Connection } from "./types";

/** 展示身份与传输连接分开；URL 与消息量不能用来推断玩家身份。 */
export function connectionLabel(connection: Partial<Connection>) {
  const identity = connection.identity;
  const parts: string[] = [];
  if (connection.note) parts.push(connection.note);
  if (identity?.state === IdentityState.IDENTIFIED) {
    if (identity.nickname) parts.push(identity.nickname);
    if (identity.serverId) parts.push(`${identity.serverId}服`);
    if (identity.roleId) parts.push(`角色 ${identity.roleId}`);
  } else
    parts.push(
      identity?.state === IdentityState.PENDING
        ? "登录中，待确认"
        : "未识别账号",
    );
  parts.push(`连接 ${(connection.id || "").slice(0, 8)}`);
  return parts.join(" · ");
}
