import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import type { Capture, Packet } from "./types.ts";
import type { FieldColumn, MessageNote } from "./analysisTools.ts";
import { useResearchNotes } from "./useResearchNotes.ts";
/** 分析面板和批注草稿独立持有，保存失败保留输入；字段列变化按现有筛选重新加载。 */
export function useResearchWorkspace(options: {
  capture: () => Capture | undefined;
  selected: () => Packet | undefined;
  canEdit: () => boolean;
  refreshMessages: () => Promise<void>;
  addWatch: (field: FieldColumn, packet: Packet | undefined) => void;
  failure: (error: unknown) => void;
}) {
  const research = useResearchNotes(options.capture),
    failure = options.failure;
  const researchOpen = ref(false),
    researchMode = ref("review");

  const noteDialog = ref(false);
  const noteDraft = ref<MessageNote>({
    index: 0,
    path: "",
    text: "",
    kind: "备注",
  });
  function openResearch(mode = "review") {
    researchMode.value = mode;
    researchOpen.value = true;
  }
  function watchField(field: FieldColumn) {
    try {
      options.addWatch(field, options.selected());
      researchOpen.value = false;
    } catch (error) {
      failure(error);
    }
  }
  async function addFieldColumn(field: FieldColumn) {
    try {
      research.addColumn(field.path, field.label);
      await options.refreshMessages();
    } catch (error) {
      failure(error);
    }
  }
  async function removeFieldColumn(path: string) {
    try {
      research.removeColumn(path);
      await options.refreshMessages();
    } catch (error) {
      failure(error);
    }
  }
  function removeNote(note: MessageNote) {
    try {
      research.removeNote(note.index, note.path);
    } catch (error) {
      failure(error);
    }
  }
  function editNote(path = "") {
    if (!options.canEdit()) {
      ElMessage.info("分析成果尚未加载，请稍后再编辑");
      return;
    }
    const selected = options.selected();
    if (!selected) return;
    noteDraft.value = {
      ...(research.notes.value.find(
        (item) => item.index === selected.index && item.path === path,
      ) || { index: selected.index, path, text: "", kind: "备注" }),
    };
    noteDialog.value = true;
  }
  function saveNote() {
    try {
      research.saveNote(noteDraft.value);
      noteDialog.value = false;
    } catch (error) {
      failure(error);
    }
  }
  const fieldAnnotations = computed(() =>
    Object.fromEntries(
      research.notes.value
        .filter((item) => item.index === options.selected()?.index)
        .map((item) => [item.path, `${item.kind}：${item.text}`]),
    ),
  );

  return {
    research,
    researchOpen,
    researchMode,
    noteDialog,
    noteDraft,
    openResearch,
    watchField,
    addFieldColumn,
    removeFieldColumn,
    removeNote,
    editNote,
    saveNote,
    fieldAnnotations,
  };
}
