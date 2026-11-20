import { ref, shallowRef } from "vue";
import {
  ResultPublication,
  type WorkspaceQuery,
} from "./workspaceInteractions.ts";
import { RequestGate } from "./workspaceRefresh.ts";
import type { Packet, Page } from "./types.ts";

/** 查询与已展示结果的唯一状态所有者；输入草稿由表单管理，成功发布前不改结果条件。 */
export function useQueryResults() {
  return {
    rows: shallowRef<Packet[]>([]),
    total: ref(0),
    page: ref(1),
    pageOffset: ref(0),
    displayedPage: ref(1),
    displayedQuery: shallowRef<WorkspaceQuery>(),
    pendingQuery: shallowRef<WorkspaceQuery>(),
    queryError: ref(""),
    displayedAt: ref(""),
    queryMetrics: ref({ queryMs: 0, lockWaitMs: 0, snapshotMs: 0 }),
    messageGate: new RequestGate(),
    queryPublication: new ResultPublication<{
      query: WorkspaceQuery;
      result: Page;
    }>(),
  };
}
