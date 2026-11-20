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
import { protocolChoice } from "./protocolSelection";
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
  content.value
    ?.querySelector<HTMLButtonElement>("button:not(:disabled)")
    ?.focus();
}
function choose(action: "connection" | "all" | "only" | "exclude") {
  const row = selected.value;
  if (
    !row ||
    (action === "connection" && !row.connection) ||
    ((action === "only" || action === "exclude") && !protocolChoice(row))
  )
    return;
  visible.value = false;
  if (action === "connection" || action === "all") emit("context", row, action);
  else if (action === "only") emit("only", row);
  else emit("exclude", row);
  target.value?.focus();
}
function close() {
  const wasVisible = visible.value;
  visible.value = false;
  if (wasVisible) target.value?.focus();
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
function moveFocus(event: KeyboardEvent) {
  const buttons = Array.from(
    content.value?.querySelectorAll<HTMLButtonElement>(
      "button:not(:disabled)",
    ) || [],
  );
  if (
    !buttons.length ||
    !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)
  )
    return;
  event.preventDefault();
  event.stopPropagation();
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
  buttons[
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? buttons.length - 1
        : (index + (event.key === "ArrowUp" ? -1 : 1) + buttons.length) %
          buttons.length
  ]?.focus();
}
function dismissOnScroll(event: Event) {
  if (visible.value && !content.value?.contains(event.target as Node))
    visible.value = false;
}
// 可见性由本组件统一管理，避免虚拟触发器和行按钮各切换一次而抵消打开动作。
onMounted(() => {
  document.addEventListener("pointerdown", closeOutside, true);
  document.addEventListener("scroll", dismissOnScroll, true);
  window.addEventListener("resize", close);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", closeOutside, true);
  document.removeEventListener("scroll", dismissOnScroll, true);
  window.removeEventListener("resize", close);
});
watch([() => props.rows, () => props.captureId], () => {
  visible.value = false;
});
defineExpose({ open, close });
</script>
<template>
  <el-popover
    :visible="visible"
    :virtual-ref="target"
    virtual-triggering
    trigger="click"
    placement="bottom-end"
    :width="210"
    :persistent="false"
    :show-arrow="false"
  >
    <div
      ref="content"
      class="message-row-actions"
      role="menu"
      aria-label="消息操作"
      @keydown.esc.stop.prevent="close"
      @keydown="moveFocus"
    >
      <div class="field-menu-heading" :title="selected?.protocolName">
        消息 #{{ selected?.index }} · {{ selected?.protocolName || "未知协议" }}
      </div>
      <el-button
        text
        role="menuitem"
        :disabled="!selected?.connection"
        :title="
          !selected?.connection
            ? '消息缺少连接信息'
            : '查看此连接的完整消息上下文'
        "
        @click="choose('connection')"
        >当前连接上下文</el-button
      >
      <el-button text role="menuitem" @click="choose('all')"
        >全部连接上下文</el-button
      >
      <div class="action-menu-divider"></div>
      <el-button
        text
        role="menuitem"
        :disabled="!selected || !protocolChoice(selected)"
        @click="choose('only')"
        >仅看此协议</el-button
      >
      <el-button
        text
        role="menuitem"
        :disabled="!selected || !protocolChoice(selected)"
        @click="choose('exclude')"
        >排除此协议</el-button
      >
    </div>
  </el-popover>
</template>
<style scoped>
.message-row-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.message-row-actions .el-button {
  margin: 0;
  justify-content: flex-start;
  height: 32px;
  padding: 0 10px;
}
.message-row-actions .el-button:not(:disabled):hover,
.message-row-actions .el-button:focus-visible {
  background: var(--soft);
  color: var(--primary);
}
</style>
