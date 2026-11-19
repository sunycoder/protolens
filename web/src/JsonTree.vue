<script setup lang="ts">
import { JSON_CHILD_PAGE_SIZE } from "./constants";
import { computed, ref, watch } from "vue";
import { pointerChild } from "./analysisWorkspace";
import JsonScalar from "./JsonScalar.vue";
import { visibleJsonKeys } from "./jsonTreeEntries";

const props = defineProps<{
  value: unknown;
  label?: string;
  depth?: number;
  path?: string;
  matchPaths?: string[];
  focusedPath?: string;
  expansionState?: Record<string, boolean>;
}>();
const emit = defineEmits<{
  expansion: [value: { path: string; open: boolean }];
}>();
function toggle(event: Event) {
  expanded.value = (event.target as HTMLDetailsElement).open;
  emit("expansion", { path: path.value, open: expanded.value });
}
const path = computed(() => props.path || "");
const matched = computed(() => props.matchPaths?.includes(path.value));
const containsMatch = computed(() =>
  props.matchPaths?.some(
    (item) => item === path.value || item.startsWith(path.value + "/"),
  ),
);
const expanded = ref((props.depth || 0) < 1);
const limit = ref<number>(JSON_CHILD_PAGE_SIZE);
watch(
  () => props.value,
  () => {
    limit.value = JSON_CHILD_PAGE_SIZE;
    expanded.value =
      props.expansionState?.[path.value] ??
      ((props.depth || 0) < 1 || !!containsMatch.value);
  },
  { immediate: true },
);
watch(containsMatch, (value) => {
  if (value) expanded.value = true;
});
watch(
  () => props.expansionState?.[path.value],
  (value) => {
    if (typeof value === "boolean") expanded.value = value;
  },
);
const objectKeys = computed(() =>
  props.value !== null &&
  typeof props.value === "object" &&
  !Array.isArray(props.value)
    ? Object.keys(props.value)
    : [],
);
const count = computed(() =>
  Array.isArray(props.value) ? props.value.length : objectKeys.value.length,
);
const summary = computed(() =>
  Array.isArray(props.value) ? `[${count.value}]` : "{" + count.value + "}",
);
const visibleKeys = computed(() =>
  expanded.value && props.value !== null && typeof props.value === "object"
    ? visibleJsonKeys(
        props.value,
        path.value,
        limit.value,
        props.matchPaths,
        objectKeys.value,
      )
    : [],
);
</script>
<template>
  <details
    v-if="value !== null && typeof value === 'object'"
    :open="expanded"
    @toggle="toggle"
    class="json-tree"
    :class="{
      'json-match': matched,
      'json-focused': focusedPath === path && matched,
    }"
    :data-json-path="path"
  >
    <summary>
      <span class="json-key">{{ label || "消息体" }}</span>
      <span class="muted">{{ summary }}</span>
    </summary>
    <JsonTree
      v-for="key in visibleKeys"
      :key="key"
      :label="key"
      :value="(value as Record<string, unknown>)[key]"
      :depth="(depth || 0) + 1"
      :path="pointerChild(path, key)"
      :match-paths="matchPaths"
      :focused-path="focusedPath"
      :expansion-state="expansionState"
      @expansion="emit('expansion', $event)"
    />
    <button
      v-if="expanded && count > limit"
      class="json-more"
      @click="limit += JSON_CHILD_PAGE_SIZE"
    >
      继续展开 {{ JSON_CHILD_PAGE_SIZE }} 项（还有 {{ count - limit }} 项）
    </button>
  </details>
  <div
    v-else
    class="json-leaf"
    :class="{
      'json-match': matched,
      'json-focused': focusedPath === path && matched,
    }"
    :data-json-path="path"
  >
    <span class="json-key">{{ label }}</span
    >:
    <JsonScalar :value="value" />
  </div>
</template>
