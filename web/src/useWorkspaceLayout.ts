import { computed, ref, watch } from "vue";
import { readStored, writeStored } from "./workspaceSession.ts";
import { validWidth } from "./workspaceNavigation.ts";

/** 列宽与详情放大只属于布局，不参与消息筛选或采集状态。 */
export function useWorkspaceLayout() {
  const saved = readStored<Record<string, unknown>>(
    "analyzer.column-widths.v1",
    {},
  );
  const widths = ref<Record<string, number>>(
    Object.fromEntries(
      Object.entries(saved && typeof saved === "object" ? saved : {}).filter(
        ([, width]) => validWidth(width),
      ),
    ) as Record<string, number>,
  );
  const maximized = ref(false);
  const resetHint = computed(() => Object.keys(widths.value).length > 0);
  function width(key: string, fallback: number) {
    return widths.value[key] || fallback;
  }
  function resize(
    size: number,
    _old: number,
    column: { property?: string; label?: string },
  ) {
    const key = column.property || column.label;
    if (!key || !validWidth(size)) return;
    widths.value = { ...widths.value, [key]: size };
  }
  function reset(key?: string) {
    if (!key) {
      widths.value = {};
      return;
    }
    const next = { ...widths.value };
    delete next[key];
    widths.value = next;
  }
  watch(widths, (value) => writeStored("analyzer.column-widths.v1", value));
  return { widths, maximized, resetHint, width, resize, reset };
}
