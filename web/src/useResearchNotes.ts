import { computed, ref } from "vue";
import { readStored, writeStored } from "./workspaceSession.ts";
import type { Capture } from "./types.ts";
import {
  MAX_FIELD_COLUMNS,
  type FieldColumn,
  type MessageNote,
} from "./analysisTools.ts";

/** 本机仅持久化路径、列标题和人工批注，不保存消息正文；记录和字典分别隔离。 */
export function useResearchNotes(capture: () => Capture | undefined) {
  const saved = readStored<{
    columns: Record<string, FieldColumn[]>;
    notes: Record<string, MessageNote[]>;
  }>("analyzer.research.v1", { columns: {}, notes: {} });
  const state = ref({
    columns:
      saved?.columns &&
      typeof saved.columns === "object" &&
      !Array.isArray(saved.columns)
        ? saved.columns
        : {},
    notes:
      saved?.notes &&
      typeof saved.notes === "object" &&
      !Array.isArray(saved.notes)
        ? saved.notes
        : {},
  });
  const dictionary = () => capture()?.catalog?.sha256 || capture()?.id || "";
  const validPath = (path: unknown) =>
    typeof path === "string" && path.startsWith("/") && path.length <= 512;
  const columns = computed(() =>
    (Array.isArray(state.value.columns[dictionary()])
      ? state.value.columns[dictionary()]
      : []
    )
      .filter(
        (item) =>
          item && validPath(item.path) && typeof item.label === "string",
      )
      .slice(0, MAX_FIELD_COLUMNS),
  );
  const notes = computed(() =>
    (Array.isArray(state.value.notes[capture()?.id || ""])
      ? state.value.notes[capture()?.id || ""]
      : []
    )
      .filter(
        (item) =>
          item &&
          Number.isSafeInteger(item.index) &&
          item.index >= 0 &&
          typeof item.path === "string" &&
          item.path.length <= 512 &&
          typeof item.text === "string" &&
          item.text.length <= 500 &&
          ["备注", "推测", "已验证"].includes(item.kind),
      )
      .slice(0, 200),
  );
  function keepActive<T>(
    values: Record<string, T>,
    active: string,
    limit: number,
  ) {
    const entries = Object.entries(values).filter(([key]) => key !== active);
    if (Object.hasOwn(values, active)) entries.push([active, values[active]]);
    return Object.fromEntries(entries.slice(-limit));
  }
  function persist(columns = state.value.columns, notes = state.value.notes) {
    if (
      Object.values(notes).reduce(
        (count, items) => count + (Array.isArray(items) ? items.length : 0),
        0,
      ) > 2000
    )
      throw new Error("本机已达到 2000 条批注上限，请先导出并移除不需要的批注");
    const next = {
      columns: keepActive(columns, dictionary(), 12),
      notes,
    };
    if (!writeStored("analyzer.research.v1", next))
      throw new Error(
        "本机存储不可写或已满，批注 / 字段列未保存，请先备份已有批注",
      );
    state.value = next;
  }
  function addColumn(path: string, label: string) {
    if (!validPath(path))
      throw new Error("请选择长度不超过 512 字符的字段路径");
    if (columns.value.some((item) => item.path === path)) return;
    if (columns.value.length >= MAX_FIELD_COLUMNS)
      throw new Error("最多添加 6 个字段列，请先移除不需要的列");
    const next = [
      ...columns.value,
      { path, label: label.slice(0, 40) || path },
    ];
    persist({ ...state.value.columns, [dictionary()]: next });
  }
  function removeColumn(path: string) {
    const next = columns.value.filter((item) => item.path !== path);
    persist({ ...state.value.columns, [dictionary()]: next });
  }
  function saveNote(note: MessageNote) {
    const id = capture()?.id;
    if (!id) return;
    if (
      !Number.isSafeInteger(note.index) ||
      note.index < 0 ||
      note.path.length > 512 ||
      !note.text.trim() ||
      note.text.length > 500
    )
      throw new Error("批注须为 1～500 字，消息编号与路径必须有效");
    const rest = notes.value.filter(
      (item) => item.index !== note.index || item.path !== note.path,
    );
    if (rest.length >= 200) throw new Error("每份记录最多保留 200 条批注");
    persist(state.value.columns, {
      ...state.value.notes,
      [id]: [...rest, { ...note, text: note.text.trim() }],
    });
  }
  function removeNote(index: number, path: string) {
    const id = capture()?.id;
    if (!id) return;
    const next = notes.value.filter(
      (item) => item.index !== index || item.path !== path,
    );
    persist(state.value.columns, { ...state.value.notes, [id]: next });
  }
  return { columns, notes, addColumn, removeColumn, saveNote, removeNote };
}
