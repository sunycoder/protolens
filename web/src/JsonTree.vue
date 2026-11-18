<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { pointerChild } from "./analysisWorkspace";
import JsonScalar from "./JsonScalar.vue";

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
  () => [props.value, props.matchPaths, props.focusedPath],
  () => {
    limit.value = 100;
    if (containsMatch.value) expanded.value = true;
  },
  { immediate: true },
);
const visibleEntries = computed(() =>
  expanded.value
    ? entries(props.value).filter(
        ([key], index) =>
          index < limit.value ||
          props.matchPaths?.some((item) => {
            const child = pointerChild(path.value, key);
            return item === child || item.startsWith(child + "/");
          }),
      )
    : [],
);

function entries(value: unknown): [string, unknown][] {
  return Object.entries(value as Record<string, unknown>);
}

function summary(value: unknown): string {
  return Array.isArray(value)
    ? "[" + value.length + "]"
    : "{" + Object.keys(value as object).length + "}";
}
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
      <span class="muted">{{ summary(value) }}</span>
    </summary>
    <JsonTree
      v-for="[key, item] in visibleEntries"
      :key="key"
      :label="key"
      :value="item"
      :depth="(depth || 0) + 1"
      :path="pointerChild(path, key)"
      :match-paths="matchPaths"
      :focused-path="focusedPath"
    />
    <button
      v-if="expanded && Object.keys(value as object).length > limit"
      class="json-more"
      @click="limit += 100"
    >
      继续展开 100 项（还有
      {{ Object.keys(value as object).length - limit }} 项）
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
