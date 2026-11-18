<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { constrainSplit } from "./splitGeometry";

/** 可复用双栏分隔器；只管理布局，不销毁槽内容，也不访问协议、会话或消息状态。 */
const props = withDefaults(
  defineProps<{
    direction: "horizontal" | "vertical";
    label: string;
    storageKey: string;
    minimumPrimary: number;
    minimumSecondary: number;
    initialRatio?: number;
    stackBelow?: number;
    secondaryCollapsed?: boolean;
    secondaryOverlay?: boolean;
  }>(),
  { initialRatio: 0.5, stackBelow: 0 },
);
const root = ref<HTMLElement>();
const extent = ref(0);
const ratio = ref(props.initialRatio);
const stacked = ref(false);
const dragging = ref(false);
let observer: ResizeObserver | undefined;
let stopGesture = () => {};
const vertical = computed(() => props.direction === "vertical");
const available = computed(() =>
  Math.max(extent.value - 7, props.minimumPrimary + props.minimumSecondary),
);
const size = computed(() =>
  constrainSplit(
    available.value,
    available.value * ratio.value,
    props.minimumPrimary,
    props.minimumSecondary,
  ),
);
const primaryStyle = computed(() =>
  props.secondaryCollapsed || props.secondaryOverlay
    ? { flex: "1 1 0" }
    : stacked.value
      ? {}
      : { flexBasis: `${size.value}px` },
);
const innerStyle = computed(() =>
  props.secondaryCollapsed || props.secondaryOverlay
    ? { minWidth: "0", minHeight: "0" }
    : stacked.value
      ? {}
      : vertical.value
        ? {
            minHeight: `${props.minimumPrimary + props.minimumSecondary + 7}px`,
          }
        : {
            minWidth: `${props.minimumPrimary + props.minimumSecondary + 7}px`,
          },
);
function save() {
  try {
    localStorage.setItem(props.storageKey, String(ratio.value));
  } catch {
    /* 布局偏好不可写不影响使用。 */
  }
}
function assign(value: number) {
  ratio.value =
    constrainSplit(
      available.value,
      value,
      props.minimumPrimary,
      props.minimumSecondary,
    ) / available.value;
}
function start(event: PointerEvent) {
  if (event.button !== 0 || stacked.value) return;
  event.preventDefault();
  stopGesture();
  dragging.value = true;
  const startPoint = vertical.value ? event.clientY : event.clientX,
    startSize = size.value;
  const oldSelection = document.body.style.userSelect;
  document.body.style.userSelect = "none";
  const move = (value: PointerEvent) =>
    assign(
      startSize + (vertical.value ? value.clientY : value.clientX) - startPoint,
    );
  const end = () => {
    stopGesture();
    save();
  };
  stopGesture = () => {
    dragging.value = false;
    document.body.style.userSelect = oldSelection;
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", end);
    window.removeEventListener("pointercancel", end);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", end, { once: true });
  window.addEventListener("pointercancel", end, { once: true });
}
function key(event: KeyboardEvent) {
  const backward = vertical.value ? "ArrowUp" : "ArrowLeft",
    forward = vertical.value ? "ArrowDown" : "ArrowRight";
  if (![backward, forward, "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  assign(
    event.key === "Home"
      ? props.minimumPrimary
      : event.key === "End"
        ? available.value - props.minimumSecondary
        : size.value +
          (event.key === backward ? -1 : 1) * (event.shiftKey ? 50 : 10),
  );
  save();
}
function reset() {
  ratio.value = props.initialRatio;
  save();
}
defineExpose({ reset });
function measure() {
  stacked.value = !!props.stackBelow && window.innerWidth <= props.stackBelow;
  extent.value =
    (vertical.value ? root.value?.clientHeight : root.value?.clientWidth) || 0;
}
function restoreRatio() {
  ratio.value = props.initialRatio;
  try {
    const stored = Number(localStorage.getItem(props.storageKey));
    if (stored > 0 && stored < 1) ratio.value = stored;
  } catch {
    /* 布局偏好不可读时使用默认比例。 */
  }
}
watch(
  () => [props.direction, props.storageKey],
  async () => {
    stopGesture();
    restoreRatio();
    await nextTick();
    measure();
  },
);
watch(
  () => props.secondaryCollapsed,
  async () => {
    stopGesture();
    await nextTick();
    measure();
  },
);
onMounted(() => {
  restoreRatio();
  observer = new ResizeObserver(measure);
  if (root.value) observer.observe(root.value);
  window.addEventListener("resize", measure);
  measure();
});
onBeforeUnmount(() => {
  stopGesture();
  observer?.disconnect();
  window.removeEventListener("resize", measure);
});
</script>
<template>
  <div
    ref="root"
    class="split-pane"
    :class="{
      vertical,
      stacked,
      dragging,
      'secondary-collapsed': secondaryCollapsed,
    }"
  >
    <div class="split-inner" :style="innerStyle">
      <div class="split-primary" :style="primaryStyle">
        <slot name="primary" />
      </div>
      <div
        v-if="!stacked && !secondaryCollapsed && !secondaryOverlay"
        class="split-handle"
        role="separator"
        tabindex="0"
        :aria-label="label"
        :aria-orientation="vertical ? 'horizontal' : 'vertical'"
        :aria-valuenow="Math.round(size)"
        :aria-valuemin="minimumPrimary"
        :aria-valuemax="Math.round(available - minimumSecondary)"
        :title="`${label}；双击恢复默认，方向键微调`"
        @pointerdown="start"
        @keydown="key"
        @dblclick="reset"
      >
        <i></i>
      </div>
      <div
        v-show="!secondaryCollapsed"
        class="split-secondary"
        :class="{ 'split-overlay': secondaryOverlay }"
      >
        <slot name="secondary" />
      </div>
    </div>
  </div>
</template>
<style scoped>
.split-pane {
  position: relative;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}
.split-secondary.split-overlay {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(560px, 94%);
  z-index: 20;
  background: var(--surface);
  border-left: 1px solid var(--border);
  box-shadow: -6px 0 20px #0002;
}
.split-inner {
  display: flex;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.vertical > .split-inner {
  flex-direction: column;
}
.split-primary,
.split-secondary {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.split-primary {
  flex-grow: 0;
  flex-shrink: 0;
}
.split-secondary {
  flex: 1;
}
.split-primary > :deep(*),
.split-secondary > :deep(*) {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.split-handle {
  flex: 0 0 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--workspace-canvas, var(--background));
  cursor: col-resize;
  touch-action: none;
  border-left: 0;
  border-right: 0;
  outline-offset: -2px;
}
.vertical .split-handle {
  cursor: row-resize;
  border: 0;
  border-top: 0;
  border-bottom: 0;
}
.split-handle i {
  width: 3px;
  height: 24px;
  border-radius: 2px;
  background: var(--border);
}
.vertical .split-handle i {
  height: 3px;
  width: 32px;
}
.split-handle:hover,
.split-handle:focus-visible,
.dragging > .split-inner > .split-handle {
  background: var(--soft);
}
.split-handle:hover i,
.split-handle:focus-visible i,
.dragging > .split-inner > .split-handle i {
  background: var(--primary);
}
.stacked {
  height: auto;
  overflow: visible;
}
.stacked > .split-inner {
  display: block;
  height: auto;
}
.stacked .split-primary,
.stacked .split-secondary {
  overflow: visible;
  display: block;
}
</style>
