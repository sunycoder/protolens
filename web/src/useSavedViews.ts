import { computed, ref, type Ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { Capture } from "./types.ts";
import {
  readColumns,
  type defaultColumns,
  type WorkspaceQuery,
} from "./workspaceInteractions.ts";
import {
  readStored,
  writeStored,
  validQuery,
  type SavedView,
} from "./workspaceSession.ts";
/** 常用视图只保存筛选和列设置，应用成功前不更换显示列。 */
export function useSavedViews(options: {
  capture: () => Capture | undefined;
  query: () => WorkspaceQuery | undefined;
  columns: Ref<typeof defaultColumns>;
  quickQuery: (query: Partial<WorkspaceQuery>) => Promise<void>;
  queryError: Ref<string>;
  failure: (error: unknown) => void;
}) {
  const capture = computed(options.capture),
    displayedQuery = computed(options.query);
  const { columns, quickQuery, queryError, failure } = options;
  const savedViews = ref<SavedView[]>(
    readStored<unknown>("analyzer.views.v1", []) instanceof Array
      ? readStored<SavedView[]>("analyzer.views.v1", [])
          .filter(
            (view) =>
              view &&
              typeof view.name === "string" &&
              typeof view.dictionary === "string" &&
              validQuery(view.query),
          )
          .slice(0, 12)
      : [],
  );
  const viewDialog = ref(false);
  async function saveView() {
    if (!displayedQuery.value) return;
    try {
      const result = await ElMessageBox.prompt(
        "保存已生效筛选与当前列设置；不包含待提交草稿。",
        "保存常用视图",
        {
          inputValidator: (value) =>
            (!!value?.trim() && value.length <= 40) || "请输入 1～40 字名称",
        },
      );
      const view: SavedView = {
        name: result.value.trim(),
        query: {
          ...displayedQuery.value,
          connection: "",
          range: "",
          messageIndex: "",
        },
        columns: { ...columns.value },
        dictionary: capture.value?.catalog?.sha256 || "",
      };
      const next = [
        view,
        ...savedViews.value.filter((item) => item.name !== view.name),
      ].slice(0, 12);
      if (!writeStored("analyzer.views.v1", next))
        throw new Error("本机存储不可用，视图未保存");
      savedViews.value = next;
      ElMessage.success("视图已保存，不包含连接和区间等记录专属条件");
    } catch (error) {
      if (error !== "cancel" && error !== "close") failure(error);
    }
  }
  async function applyView(view: SavedView) {
    if (view.dictionary && view.dictionary !== capture.value?.catalog?.sha256) {
      try {
        await ElMessageBox.confirm(
          "此视图来自另一份协议字典，协议 ID 含义可能不同。确认应用？",
          "字典不同",
        );
      } catch {
        return;
      }
    }
    await quickQuery(view.query);
    if (!queryError.value) {
      columns.value = readColumns(JSON.stringify(view.columns));
      viewDialog.value = false;
    }
  }
  function deleteView(name: string) {
    const next = savedViews.value.filter((item) => item.name !== name);
    if (writeStored("analyzer.views.v1", next)) savedViews.value = next;
  }

  return { savedViews, viewDialog, saveView, applyView, deleteView };
}
