import {
  computed,
  nextTick,
  onMounted,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
  type Ref,
} from "vue";
import { ElMessage, type TableInstance } from "element-plus";
import SplitPane from "./SplitPane.vue";
import type { Packet } from "./types.ts";
import {
  defaultColumns,
  readColumns,
  listMinimumWidth,
  chooseDetailLayout,
  type DetailLayout,
} from "./workspaceInteractions.ts";
/** 工作区尺寸与布局偏好独立管理；重排前后恢复列表滚动，不改变选中内容。 */
export function useWorkbenchLayout(options: {
  detailOpen: Ref<boolean>;
  hasCapture: () => boolean;
  fieldCount: () => number;
  batchExcluding: () => boolean;
  table: () => TableInstance | undefined;
  scrollTop: () => number;
  selectedRow: () => Packet | undefined;
  resetFilterPresentation: () => void;
}) {
  const { detailOpen } = options;
  const columns = ref(readColumns(localStorage.getItem("analyzer.columns.v3")));
  const tableRevision = ref(0);
  const splitPane = ref<InstanceType<typeof SplitPane>>();
  const dataRegion = ref<HTMLElement>();
  const regionSize = shallowRef({
    width: window.innerWidth,
    height: window.innerHeight - 190,
  });
  let regionObserver: ResizeObserver | undefined;
  const sidebarCollapsed = ref(
    localStorage.getItem("analyzer.sidebarCollapsed") !== "false",
  );
  const focusMode = ref(false);
  const savedDock = localStorage.getItem("analyzer.layoutPreference.v3");
  const detailDock = ref<DetailLayout>(
    ["auto", "right", "bottom", "overlay"].includes(savedDock || "")
      ? (savedDock as DetailLayout)
      : "auto",
  );
  const listWidth = computed(
    () =>
      listMinimumWidth(columns.value) +
      options.fieldCount() * 150 +
      (options.batchExcluding() ? 36 : 0),
  );
  const effectiveLayout = computed(() =>
    chooseDetailLayout(
      regionSize.value.width,
      regionSize.value.height,
      detailDock.value,
      listWidth.value,
    ),
  );
  const detailDirection = computed(() =>
    effectiveLayout.value === "bottom" ? "vertical" : "horizontal",
  );
  const sidebarVisible = computed(
    () =>
      !focusMode.value && (!sidebarCollapsed.value || !options.hasCapture()),
  );
  const detailVisible = computed(() => detailOpen.value && !focusMode.value);
  function measureWorkspace() {
    const rect = dataRegion.value?.getBoundingClientRect();
    if (rect)
      regionSize.value = {
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      };
  }
  watch(sidebarCollapsed, (value) =>
    localStorage.setItem("analyzer.sidebarCollapsed", String(value)),
  );
  watch(detailDock, (value) =>
    localStorage.setItem("analyzer.layoutPreference.v3", value),
  );
  watch(
    columns,
    (value) =>
      localStorage.setItem("analyzer.columns.v3", JSON.stringify(value)),
    { deep: true },
  );
  watch([detailDirection, detailVisible, sidebarVisible], async () => {
    const top = options.scrollTop();
    await nextTick();
    options.table()?.doLayout();
    options.table()?.setScrollTop(top);
  });
  async function resetLayout() {
    const top = options.scrollTop();
    columns.value = { ...defaultColumns };
    detailDock.value = "auto";
    sidebarCollapsed.value = true;
    focusMode.value = false;
    options.resetFilterPresentation();
    for (const direction of ["horizontal", "vertical"])
      localStorage.removeItem(`analyzer.split.${direction}.v3`);
    splitPane.value?.reset();
    tableRevision.value++;
    await nextTick();
    options.table()?.setCurrentRow(options.selectedRow());
    options.table()?.setScrollTop(top);
    ElMessage.success("已恢复默认布局，消息、查询条件和选中行已保留");
  }

  onMounted(() => {
    window.addEventListener("resize", measureWorkspace);
    regionObserver = new ResizeObserver(measureWorkspace);
    if (dataRegion.value) regionObserver.observe(dataRegion.value);
    measureWorkspace();
  });
  onBeforeUnmount(() => {
    window.removeEventListener("resize", measureWorkspace);
    regionObserver?.disconnect();
  });
  return {
    columns,
    tableRevision,
    splitPane,
    dataRegion,
    sidebarCollapsed,
    focusMode,
    detailDock,
    listWidth,
    effectiveLayout,
    detailDirection,
    sidebarVisible,
    detailVisible,
    resetLayout,
  };
}
