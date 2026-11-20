import { computed, nextTick, ref, watch, type Ref } from "vue";
import type { useMessageSelection } from "./useMessageSelection.ts";
import { findFieldMatches } from "./analysisWorkspace.ts";
import { useInlineCopy } from "./useInlineCopy.ts";
/** 当前消息的阅读状态：树展开、字段定位、详情搜索和复制独立于列表查询。 */
export function useMessageInspector(options: {
  selection: ReturnType<typeof useMessageSelection>;
  tab: Ref<string>;
  captureId: () => string | undefined;
  listQuery: () => string;
  restoring: () => boolean;
}) {
  const { selected } = options.selection;
  const tab = options.tab,
    detailLocalQuery = ref("");
  const detailQuery = computed(
    () => detailLocalQuery.value || options.listQuery() || "",
  );
  const fieldMatches = computed(() =>
    findFieldMatches(selected.value?.body, detailQuery.value),
  );
  const matchPaths = computed(() =>
    fieldMatches.value.matches.map((item) => item.path),
  );
  const focusedField = ref("");
  const expandedFields = ref<Record<string, boolean>>({});
  const fieldExpansionCache = new Map<string, Record<string, boolean>>();
  watch(
    () => selected.value && `${options.captureId()}:${selected.value.index}`,
    (key) => {
      if (!key) return;
      let saved = fieldExpansionCache.get(key);
      if (!saved) {
        saved = {};
        fieldExpansionCache.set(key, saved);
      }
      expandedFields.value = saved;
      if (fieldExpansionCache.size > 8)
        fieldExpansionCache.delete(fieldExpansionCache.keys().next().value!);
    },
  );
  function rememberExpansion(value: { path: string; open: boolean }) {
    if (
      value.path.length <= 2048 &&
      (Object.hasOwn(expandedFields.value, value.path) ||
        Object.keys(expandedFields.value).length < 500)
    )
      expandedFields.value[value.path] = value.open;
  }

  async function focusField(path: string, expandAncestors = true) {
    const target = selected.value;
    focusedField.value = "";
    await nextTick();
    if (selected.value !== target) return;
    if (expandAncestors) {
      rememberExpansion({ path: "", open: true });
      let parent = "";
      for (const part of path.split("/").slice(1, -1)) {
        parent += "/" + part;
        rememberExpansion({ path: parent, open: true });
      }
    }
    focusedField.value = path;
    tab.value = "tree";
    await nextTick();
    const node = Array.from(
      document.querySelectorAll<HTMLElement>(".detail-panel [data-json-path]"),
    ).find((item) => item.dataset.jsonPath === path);
    node?.scrollIntoView({ block: "center", inline: "nearest" });
  }
  watch([selected, detailQuery], async () => {
    if (options.restoring()) return;
    focusedField.value = fieldMatches.value.matches[0]?.path || "";
    if (focusedField.value && tab.value === "tree")
      await focusField(focusedField.value);
  });
  const detailMetadata = computed(() => {
    if (!selected.value) return {};
    const { body, schema, packetHex, wireHex, ...metadata } = selected.value;
    return metadata;
  });
  const detailCopy = useInlineCopy();
  watch(selected, detailCopy.reset);
  async function copy() {
    await detailCopy.copy(pretty(selected.value?.body ?? selected.value));
  }

  return {
    tab,
    detailLocalQuery,
    detailQuery,
    fieldMatches,
    matchPaths,
    focusedField,
    expandedFields,
    rememberExpansion,
    focusField,
    detailMetadata,
    detailCopy,
    copy,
  };
}
function pretty(value: unknown) {
  return JSON.stringify(value, null, 2) ?? "";
}
