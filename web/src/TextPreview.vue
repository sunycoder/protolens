<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
const props = withDefaults(
  defineProps<{ value: unknown; format?: "json" | "hex" }>(),
  { format: "json" },
);
const text = ref("");
const limit = ref(32768);
const busy = ref(false);
const error = ref("");
let worker: Worker | undefined;
function formatText() {
  worker?.terminate();
  text.value = "";
  limit.value = 32768;
  busy.value = true;
  error.value = "";
  try {
    const current = new Worker(
      new URL("./textPreview.worker.ts", import.meta.url),
      { type: "module" },
    );
    worker = current;
    current.onmessage = (event) => {
      if (worker !== current) return;
      busy.value = false;
      text.value = event.data.text || "";
      error.value = event.data.error || "";
      current.terminate();
    };
    current.onerror = () => {
      if (worker === current) {
        busy.value = false;
        error.value = "文本格式化失败，可重试或使用 JSON 树";
        current.terminate();
      }
    };
    current.postMessage({ value: props.value, format: props.format });
  } catch (cause) {
    busy.value = false;
    error.value = String(cause);
  }
}
watch(() => [props.value, props.format], formatText, { immediate: true });
onBeforeUnmount(() => {
  worker?.terminate();
  worker = undefined;
});
</script>
<template>
  <div class="text-preview">
    <p v-if="busy" role="status">正在格式化，可切换其他消息或标签。</p>
    <p v-if="error" class="error-bar">
      {{ error }} <el-button @click="formatText">重试</el-button>
    </p>
    <pre>{{ text.slice(0, limit) }}</pre>
    <el-button v-if="text.length > limit" @click="limit += 32768"
      >继续展示 32K 字符（已展示 {{ limit }} / {{ text.length }}）</el-button
    >
  </div>
</template>
