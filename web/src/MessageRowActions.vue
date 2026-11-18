<script setup lang="ts">
import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import type { Packet } from "./types";
const props = defineProps<{ rows: Packet[]; captureId?: string }>();
const emit = defineEmits<{
  context: [row: Packet, scope: "connection" | "all"];
  only: [row: Packet];
  exclude: [row: Packet];
  opening: [];
}>();
const visible = ref(false);
const target = shallowRef<HTMLElement>();
const selected = shallowRef<Packet>();
const content = ref<HTMLElement>();
// 全页共用一个浮层；每行只保留原生按钮，不为百条消息各创建下拉菜单。
async function open(event: MouseEvent, row: Packet) {
  if (visible.value && selected.value === row) {
    visible.value = false;
    return;
  }
  target.value = event.currentTarget as HTMLElement;
  selected.value = row;
  // 在显示浮层之前同步冻结列表，同时使已发出的实时查询失效。
  emit("opening");
  visible.value = true;
  await nextTick();
  content.value?.querySelector<HTMLButtonElement>("button")?.focus();
}
function choose(action: "connection" | "all" | "only" | "exclude") {
  const row = selected.value;
  visible.value = false;
  if (!row) return;
  if (action === "connection" || action === "all") emit("context", row, action);
  else if (action === "only") emit("only", row);
  else emit("exclude", row);
  target.value?.focus();
}
function close() {
  visible.value = false;
  target.value?.focus();
}
function closeOutside(event: PointerEvent) {
  const node = event.target as Node | null;
  if (
    visible.value &&
    node &&
    !content.value?.contains(node) &&
    !target.value?.contains(node)
  )
    visible.value = false;
}
// 可见性由本组件统一管理，避免虚拟触发器和行按钮各切换一次而抵消打开动作。
onMounted(() => document.addEventListener("pointerdown", closeOutside, true));
onBeforeUnmount(() =>
  document.removeEventListener("pointerdown", closeOutside, true),
);
watch([() => props.rows, () => props.captureId], () => {
  visible.value = false;
});
defineExpose({ open, close });
</script>
<template>
  <el-popover
    role="dialog"
    title="消息操作"
    :visible="visible"
    :virtual-ref="target"
    virtual-triggering
    trigger="click"
    placement="bottom-end"
    :width="180"
    :persistent="false"
    :show-arrow="false"
  >
    <div
      ref="content"
      class="message-row-actions"
      @keydown.esc.stop.prevent="close"
    >
      <button @click="choose('connection')">当前连接上下文</button>
      <button @click="choose('all')">全部连接上下文</button>
      <button @click="choose('only')">仅看此协议</button>
      <button @click="choose('exclude')">排除此协议</button>
    </div>
  </el-popover>
</template>
<style scoped>
.message-row-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.message-row-actions button {
  padding: 7px 10px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.message-row-actions button:hover,
.message-row-actions button:focus-visible {
  background: var(--soft);
  color: var(--primary);
}
</style>
