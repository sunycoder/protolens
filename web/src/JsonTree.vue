<script setup lang="ts">
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
}>();
const path = computed(() => props.path || "");
const matched = computed(() => props.matchPaths?.includes(path.value));
const containsMatch = computed(() =>
  props.matchPaths?.some(
    (item) => item === path.value || item.startsWith(path.value + "/"),
  ),
);
const expanded = ref((props.depth || 0) < 1);
const limit = ref(100);
watch(
  () => props.value,
  () => {
    limit.value = 100;
    expanded.value = (props.depth || 0) < 1 || !!containsMatch.value;
  },
  { immediate: true },
);
watch(containsMatch, (value) => {
  if (value) expanded.value = true;
});
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
    @toggle="expanded = ($event.target as HTMLDetailsElement).open"
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
    />
    <button
      v-if="expanded && count > limit"
      class="json-more"
      @click="limit += 100"
    >
      继续展开 100 项（还有
      {{ count - limit }} 项）
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
