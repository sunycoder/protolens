import { nextTick, onScopeDispose, ref, watch } from "vue";
import { api, post } from "./types.ts";
export interface ProjectDocument {
  revision: number;
  notes: unknown[];
  columns: unknown[];
  views: unknown[];
  widths: Record<string, number>;
}

/** 分析成果与记录绑定：读后再写、同一记录串行保存、冲突保留本地输入，不用旧页面覆盖新版成果。 */
export function useProjectSync(options: {
  id: () => string | undefined;
  enabled: () => boolean;
  document: () => Omit<ProjectDocument, "revision">;
  apply: (value: ProjectDocument) => void;
}) {
  const ready = ref(false),
    pending = ref(false),
    saving = ref(false),
    error = ref(""),
    savedAt = ref(0),
    revision = ref(0);
  let version = 0,
    dirty = false,
    hydrating = false,
    blocked = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let active: Promise<void> | undefined;
  async function load() {
    const run = ++version,
      id = options.id();
    ready.value = false;
    error.value = "";
    savedAt.value = 0;
    revision.value = 0;
    dirty = false;
    pending.value = false;
    blocked = false;
    active = undefined;
    saving.value = false;
    if (timer) clearTimeout(timer);
    timer = undefined;
    if (!id || !options.enabled()) return;
    try {
      const result = await api<ProjectDocument>(`/api/captures/${id}/research`);
      if (run !== version) return;
      hydrating = true;
      if (result.revision > 0) options.apply(result);
      revision.value = result.revision;
      await nextTick();
      hydrating = false;
      ready.value = true;
      if (result.revision === 0) schedule();
    } catch (cause) {
      if (run === version) {
        hydrating = false;
        error.value = String(cause);
      }
    }
  }
  function schedule() {
    if (hydrating || !ready.value || blocked) return;
    dirty = true;
    pending.value = true;
    if (active || timer) return;
    timer = setTimeout(() => {
      timer = undefined;
      void save();
    }, 350);
  }
  async function save() {
    if (!ready.value || blocked || !dirty) return;
    if (active) {
      await active;
      return;
    }
    const id = options.id(),
      run = version;
    if (!id) return;
    const document = JSON.parse(
      JSON.stringify({ ...options.document(), revision: revision.value }),
    ) as ProjectDocument;
    dirty = false;
    saving.value = true;
    active = (async () => {
      try {
        const result = await post<ProjectDocument>(
          `/api/captures/${id}/research`,
          document,
        );
        if (run !== version) return;
        revision.value = result.revision;
        savedAt.value = Date.now();
        error.value = "";
      } catch (cause) {
        if (run === version) {
          error.value = String(cause);
          blocked = true;
        }
      } finally {
        if (run === version) {
          active = undefined;
          saving.value = false;
          pending.value = dirty || blocked;
          if (dirty && !blocked) schedule();
        }
      }
    })();
    await active;
  }
  async function flush() {
    await nextTick();
    if (timer) clearTimeout(timer);
    timer = undefined;
    if (active) await active;
    if (!ready.value)
      throw new Error(error.value || "分析成果尚未加载，请稍后重试");
    if (error.value) throw new Error(error.value);
    if (dirty) await save();
    if (error.value) throw new Error(error.value);
  }
  watch(
    () => `${options.id() || ""}:${options.enabled()}`,
    () => void load(),
    { immediate: true },
  );
  watch(() => JSON.stringify(options.document()), schedule);
  onScopeDispose(() => {
    version++;
    if (timer) clearTimeout(timer);
  });
  return {
    ready,
    pending,
    saving,
    error,
    savedAt,
    revision,
    flush,
    reload: load,
  };
}
