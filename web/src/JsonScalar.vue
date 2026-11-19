<script setup lang="ts">
import { SCALAR_PREVIEW_LENGTH, SCALAR_EXPANSION_CHUNK } from "./constants";
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
const props = defineProps<{ value: unknown }>();
const limit = ref(SCALAR_PREVIEW_LENGTH);
const text = computed(() => JSON.stringify(props.value) ?? String(props.value));
const visible = computed(() => text.value.slice(0, limit.value));
watch(
  () => props.value,
  () => {
    limit.value = SCALAR_PREVIEW_LENGTH;
  },
);
async function copyValue() {
  try {
    await navigator.clipboard.writeText(
      typeof props.value === "string" ? props.value : text.value,
    );
    ElMessage.success("已复制完整字段值");
  } catch {
    ElMessage.error("复制失败，请展开字段值后手动复制");
  }
}
</script>
<template>
  <span class="json-scalar">
    <span :class="typeof value === 'string' ? 'json-string' : 'json-number'">{{
      visible
    }}</span>
    <span v-if="text.length > limit" class="muted"
      >…（剩余 {{ text.length - limit }} 字符）</span
    >
    <button
      v-if="text.length > limit"
      class="json-value-action"
      @click.stop="limit += SCALAR_EXPANSION_CHUNK"
    >
      {{ limit === SCALAR_PREVIEW_LENGTH ? "展开值" : "继续展开" }}
    </button>
    <button
      v-if="limit > SCALAR_PREVIEW_LENGTH"
      class="json-value-action"
      @click.stop="limit = SCALAR_PREVIEW_LENGTH"
    >
      收起值
    </button>
    <button
      class="json-value-action json-copy-value"
      @click.stop="copyValue"
      title="复制完整字段值"
    >
      复制值
    </button>
  </span>
</template>
