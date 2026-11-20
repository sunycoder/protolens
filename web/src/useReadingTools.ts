import { nextTick, ref, shallowRef, watch } from "vue";
import type { TableInstance } from "element-plus";
import type { Packet } from "./types.ts";
import type { ReadingPlace } from "./workspaceSession.ts";
import type { ReadingCheckpoint } from "./workspaceNavigation.ts";
import { useWorkspaceLayout } from "./useWorkspaceLayout.ts";

/** 阅读交互状态独立于列表查询：放大、范围选择、组预览与返回位置不持有或改写消息数据。 */
export function useReadingTools(options: {
  id: () => string | undefined;
  table: () => TableInstance | undefined;
  place: () => ReadingPlace | undefined;
  following: () => boolean;
  field: () => string;
  restore: (checkpoint: ReadingCheckpoint) => Promise<boolean>;
  select: (row: Packet) => Promise<void>;
  inspect: (row: Packet) => Promise<void>;
  pause: () => void;
  rebuild: () => void;
}) {
  const layout = useWorkspaceLayout(),
    navigationReturn = shallowRef<ReadingCheckpoint>(),
    restoring = ref(false);
  const inspecting = ref(false);
  const rangeAnchor = ref<number>(),
    selectedRange = ref<{ from: number; to: number }>();
  const previewGroup = ref<number>(),
    previewOpen = ref(false);
  let groupScroll = 0;
  function scroll() {
    return (
      options
        .table()
        ?.$el.querySelector(".el-table__body-wrapper .el-scrollbar__wrap")
        ?.scrollTop || 0
    );
  }
  function checkpoint() {
    if (restoring.value) return;
    const place = options.place();
    if (place)
      navigationReturn.value = {
        place,
        following: options.following(),
        field: options.field(),
      };
  }
  async function back() {
    const previous = navigationReturn.value;
    if (!previous || previous.place.captureId !== options.id()) return;
    restoring.value = true;
    try {
      if (await options.restore(previous)) navigationReturn.value = undefined;
    } finally {
      restoring.value = false;
    }
  }
  async function maximize() {
    const top = scroll();
    layout.maximized.value = !layout.maximized.value;
    await nextTick();
    options.table()?.doLayout();
    options.table()?.setScrollTop(top);
  }
  async function rowClick(row: Packet, _column: unknown, event: MouseEvent) {
    if (event.shiftKey && rangeAnchor.value !== undefined) {
      options.pause();
      selectedRange.value = {
        from: Math.min(rangeAnchor.value, row.index),
        to: Math.max(rangeAnchor.value, row.index),
      };
      return;
    }
    rangeAnchor.value = row.index;
    selectedRange.value = undefined;
    await options.select(row);
  }
  function preview(index: number) {
    groupScroll = scroll();
    previewGroup.value = index;
    previewOpen.value = true;
  }
  async function closePreview() {
    previewOpen.value = false;
    await nextTick();
    options.table()?.setScrollTop(groupScroll);
  }
  async function inspect(row: Packet) {
    previewOpen.value = false;
    inspecting.value = true;
    try {
      await options.inspect(row);
      await nextTick();
      await closePreview();
    } finally {
      inspecting.value = false;
    }
  }
  async function resetColumn(
    column: { property?: string; label?: string },
    event: MouseEvent,
  ) {
    const target = (event.target as Element)?.closest("th");
    const rect = target?.getBoundingClientRect();
    if (rect && event.clientX < rect.right - 12) return;
    layout.reset(column.property || column.label);
    options.rebuild();
    await nextTick();
    options.table()?.doLayout();
  }
  watch(options.id, () => {
    layout.maximized.value = false;
    navigationReturn.value = undefined;
    rangeAnchor.value = undefined;
    selectedRange.value = undefined;
    previewOpen.value = false;
  });
  return {
    layout,
    navigationReturn,
    restoring,
    selectedRange,
    previewGroup,
    previewOpen,
    checkpoint,
    back,
    maximize,
    rowClick,
    preview,
    closePreview,
    inspect,
    inspecting,
    resetColumn,
  };
}
