<script setup lang="ts">
import { watch } from "vue";
import { useInlineCopy } from "./useInlineCopy";
const props = defineProps<{ value: string; label?: string }>();
const { feedback, copy, reset } = useInlineCopy();
watch(() => props.value, reset);
</script>
<template>
  <span class="copy-text"
    ><span class="copy-text-value" :title="value">{{ value }}</span
    ><button
      class="json-value-action"
      :aria-label="`复制${label || '完整值'}`"
      :title="value"
      @click.stop="copy(value)"
    >
      {{ feedback || "复制" }}</button
    ><span class="sr-only" role="status">{{ feedback }}</span></span
  >
</template>
<style scoped>
.copy-text {
  display: inline-flex;
  align-items: baseline;
  max-width: 100%;
  gap: 4px;
  vertical-align: baseline;
}
.copy-text-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 15em;
}
</style>
