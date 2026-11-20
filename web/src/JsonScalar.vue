<script setup lang="ts">
import { SCALAR_PREVIEW_LENGTH, SCALAR_EXPANSION_CHUNK } from "./constants";
import { computed, ref, watch } from "vue";
import { useInlineCopy } from "./useInlineCopy";
import { formatScalar, type ValueFormat } from "./analysisTools";
const inlineCopy = useInlineCopy();
const props = defineProps<{ value: unknown }>();
const limit = ref(SCALAR_PREVIEW_LENGTH);
const format = ref<ValueFormat>("raw");
const formatOpen = ref(false);
const converted = computed(() => formatScalar(props.value, format.value));
const text = computed(() => JSON.stringify(props.value) ?? String(props.value));
const visible = computed(() => text.value.slice(0, limit.value));
watch(
  () => props.value,
  () => {
    limit.value = SCALAR_PREVIEW_LENGTH;
    inlineCopy.reset();
    format.value = "raw";
  },
);
async function copyValue() {
  await inlineCopy.copy(
    typeof props.value === "string" ? props.value : text.value,
  );
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
      {{ inlineCopy.feedback.value || "复制值" }}
    </button>
    <el-select
      v-if="typeof value === 'number' || typeof value === 'string'"
      v-model="format"
      class="json-value-format"
      :class="{ 'is-converted': format !== 'raw', 'is-open': formatOpen }"
      @visible-change="formatOpen = $event"
      size="small"
      aria-label="字段显示格式"
      @click.stop
    >
      <el-option value="raw" label="原值" />
      <el-option value="hex" label="十六进制" />
      <el-option value="bytes" label="字节数" />
      <el-option value="unixms" label="时间戳（毫秒）" />
      <el-option value="unixs" label="时间戳（秒）" />
    </el-select>
    <span v-if="converted" class="json-converted"> → {{ converted }}</span>
  </span>
</template>
