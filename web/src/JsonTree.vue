<script setup lang="ts">
import { computed, ref, watch } from "vue";

const props = defineProps<{ value: unknown; label?: string; depth?: number }>();
const expanded = ref((props.depth || 0) < 1);
const limit = ref(100);
watch(
  () => props.value,
  () => {
    limit.value = 100;
  },
);
const visibleEntries = computed(() =>
  expanded.value ? entries(props.value).slice(0, limit.value) : [],
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
  <div v-else class="json-leaf">
    <span class="json-key">{{ label }}</span
    >:
    <span :class="typeof value === 'string' ? 'json-string' : 'json-number'">{{
      JSON.stringify(value)
    }}</span>
  </div>
</template>
