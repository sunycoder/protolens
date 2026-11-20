import { ref, watch } from "vue";
import type { ReadingPlace } from "./workspaceSession.ts";
import type { WorkspaceQuery } from "./workspaceInteractions.ts";
export interface TemporaryView {
  name: string;
  origin: WorkspaceQuery;
  applied: WorkspaceQuery;
}
export interface AnalysisTab {
  id: number;
  name: string;
  place?: ReadingPlace;
  following: boolean;
  field: string;
  temporary?: TemporaryView;
}
/** 页签是阅读位置的唯一所有者；非当前页签不请求消息，失败不会关闭原页签或发布半套位置。 */
export function useAnalysisTabs(options: {
  id: () => string | undefined;
  place: () => ReadingPlace | undefined;
  follow: () => boolean;
  field: () => string;
  temporary: () => TemporaryView | undefined;
  restore: (tab: AnalysisTab) => Promise<boolean>;
}) {
  const tabs = ref<AnalysisTab[]>([]),
    active = ref(0),
    switching = ref(false);
  let sequence = 0;
  function remember() {
    if (switching.value) return;
    const tab = tabs.value.find((item) => item.id === active.value),
      place = options.place();
    if (tab && place) {
      tab.place = place;
      tab.following = options.follow();
      tab.field = options.field();
      tab.temporary = options.temporary();
    }
  }
  watch(
    options.id,
    () => {
      tabs.value = [
        { id: ++sequence, name: "全部消息", following: true, field: "" },
      ];
      active.value = sequence;
    },
    { immediate: true },
  );
  function add() {
    if (tabs.value.length >= 5) throw new Error("最多打开 5 个临时分析页签");
    remember();
    const place = options.place();
    if (!place) return;
    const id = ++sequence;
    tabs.value.push({
      id,
      name: `分析 ${tabs.value.length + 1}`,
      place,
      following: options.follow(),
      field: options.field(),
      temporary: options.temporary(),
    });
    active.value = id;
  }
  async function select(id: number) {
    if (id === active.value || switching.value) return;
    remember();
    const tab = tabs.value.find((item) => item.id === id);
    if (!tab) return;
    const record = options.id();
    switching.value = true;
    try {
      if ((await options.restore(tab)) && options.id() === record)
        active.value = id;
    } finally {
      switching.value = false;
    }
  }
  async function close(id: number) {
    if (tabs.value.length <= 1 || switching.value) return;
    if (id === active.value) {
      await select(tabs.value.find((item) => item.id !== id)!.id);
      if (active.value === id) return;
    }
    tabs.value = tabs.value.filter((item) => item.id !== id);
  }
  function rename(id: number, name: string) {
    const tab = tabs.value.find((item) => item.id === id);
    if (tab && name.trim() && name.length <= 20) tab.name = name.trim();
  }
  return { tabs, active, switching, remember, add, select, close, rename };
}
