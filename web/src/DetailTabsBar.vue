<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { ScrollbarInstance } from "element-plus";
const model = defineModel<string>({ required: true });
const tabs = [
  { name: "tree", label: "JSON 树" },
  { name: "json", label: "JSON 文本" },
  { name: "hex", label: "原始包体" },
  { name: "schema", label: "字段定义" },
  { name: "metadata", label: "元数据" },
];
const viewport = ref<HTMLElement>(),
  scrollbar = ref<ScrollbarInstance>(),
  left = ref(false),
  right = ref(false);
let observer: ResizeObserver | undefined;
let stopDrag = () => {};
let suppressClick = false;
function startDrag(event: PointerEvent) {
  const node = viewport.value;
  if ((event.target as HTMLElement).closest(".el-scrollbar__bar")) return;
  if (
    !node ||
    event.button !== 0 ||
    node.scrollWidth <= node.clientWidth ||
    event.offsetY >= node.clientHeight
  )
    return;
  const origin = event.clientX,
    initial = node.scrollLeft;
  stopDrag();
  let moved = false;
  const move = (next: PointerEvent) => {
    if (!moved && Math.abs(next.clientX - origin) < 5) return;
    moved = true;
    suppressClick = true;
    node.scrollLeft = initial + origin - next.clientX;
    measure();
  };
  const end = () => {
    stopDrag();
    setTimeout(() => (suppressClick = false), 0);
  };
  stopDrag = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", end);
    window.removeEventListener("pointercancel", end);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", end, { once: true });
  window.addEventListener("pointercancel", end, { once: true });
}
function clickTab(event: MouseEvent) {
  if (suppressClick) {
    event.preventDefault();
    event.stopPropagation();
  }
}
function measure() {
  const node = viewport.value;
  left.value = !!node && node.scrollLeft > 1;
  right.value =
    !!node && node.scrollLeft + node.clientWidth < node.scrollWidth - 1;
}
function scroll(delta: number) {
  viewport.value?.scrollBy({ left: delta });
}
function wheel(event: WheelEvent) {
  const node = viewport.value;
  if (!node || event.ctrlKey || event.deltaX || !event.deltaY) return;
  if ((event.deltaY > 0 && right.value) || (event.deltaY < 0 && left.value)) {
    event.preventDefault();
    node.scrollLeft +=
      event.deltaY *
      (event.deltaMode === 1
        ? 16
        : event.deltaMode === 2
          ? node.clientWidth
          : 1);
    measure();
  }
}
function key(event: KeyboardEvent, index: number) {
  let next = index;
  if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
  else if (event.key === "ArrowLeft")
    next = (index - 1 + tabs.length) % tabs.length;
  else if (event.key === "Home") next = 0;
  else if (event.key === "End") next = tabs.length - 1;
  else return;
  event.preventDefault();
  event.stopPropagation();
  model.value = tabs[next].name;
  viewport.value
    ?.querySelectorAll<HTMLButtonElement>("[role=tab]")
    [next]?.focus();
}
watch(model, async () => {
  await nextTick();
  const node = viewport.value,
    selected = node?.querySelector<HTMLElement>('[aria-selected="true"]');
  if (node && selected) {
    if (selected.offsetLeft < node.scrollLeft)
      node.scrollLeft = selected.offsetLeft;
    else if (
      selected.offsetLeft + selected.offsetWidth >
      node.scrollLeft + node.clientWidth
    )
      node.scrollLeft =
        selected.offsetLeft + selected.offsetWidth - node.clientWidth;
  }
  measure();
});
onMounted(() => {
  viewport.value = scrollbar.value?.wrapRef;
  observer = new ResizeObserver(measure);
  if (viewport.value) observer.observe(viewport.value);
  measure();
});
onBeforeUnmount(() => {
  observer?.disconnect();
  stopDrag();
});
</script>
<template>
  <div class="detail-tab-navigation">
    <el-button
      v-if="left || right"
      text
      size="small"
      :disabled="!left"
      aria-label="向左滚动详情标签"
      @click="scroll(-140)"
      >‹</el-button
    >
    <el-scrollbar
      ref="scrollbar"
      always
      class="detail-tab-scroll"
      role="tablist"
      aria-label="消息详情视图"
      @wheel="wheel"
      @scroll="measure"
      @pointerdown="startDrag"
      @click.capture="clickTab"
    >
      <el-button
        v-for="(item, index) in tabs"
        :key="item.name"
        text
        role="tab"
        :aria-selected="model === item.name"
        :aria-controls="`pane-${item.name}`"
        :tabindex="model === item.name ? 0 : -1"
        :type="model === item.name ? 'primary' : 'default'"
        @click="model = item.name"
        @keydown="key($event, index)"
        >{{ item.label }}</el-button
      >
    </el-scrollbar>
    <el-button
      v-if="left || right"
      text
      size="small"
      :disabled="!right"
      aria-label="向右滚动详情标签"
      @click="scroll(140)"
      >›</el-button
    >
  </div>
</template>
<style scoped>
.detail-tab-navigation {
  display: flex;
  align-items: center;
  gap: 2px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  padding: 0 6px;
  min-width: 0;
}
.detail-tab-navigation > .el-button {
  flex-shrink: 0;
  margin: 0;
  width: 24px;
}
.detail-tab-scroll {
  position: relative;
  flex: 1;
  min-width: 0;
  user-select: none;
}
.detail-tab-scroll :deep(.el-scrollbar__wrap) {
  overflow-y: hidden;
}
.detail-tab-scroll :deep(.el-scrollbar__view) {
  display: flex;
  width: max-content;
  min-width: 100%;
  padding-bottom: 8px;
}
.detail-tab-scroll .el-button {
  flex-shrink: 0;
  margin: 0;
  border-radius: 0;
  height: 34px;
  padding: 0 12px;
  border-bottom: 2px solid transparent;
}
.detail-tab-scroll [aria-selected="true"] {
  border-bottom-color: var(--primary);
  background: var(--soft);
}
</style>
