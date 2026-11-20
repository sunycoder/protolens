<script setup lang="ts">
import { JSON_CHILD_PAGE_SIZE } from "./constants";
import { computed, ref, watch } from "vue";
import { pointerChild } from "./analysisWorkspace";
import JsonScalar from "./JsonScalar.vue";
import { visibleJsonKeys } from "./jsonTreeEntries";

const props = defineProps<{
  value: unknown;
  label?: string;
  depth?: number;
  path?: string;
  matchPaths?: string[];
  focusedPath?: string;
  expansionState?: Record<string, boolean>;
  annotations?: Record<string, string>;
  fieldActions?: boolean;
}>();
const emit = defineEmits<{
  expansion: [value: { path: string; open: boolean }];
  column: [value: { path: string; label: string }];
  note: [path: string];
  watchField: [value: { path: string; label: string }];
  focus: [path: string];
}>();
const fieldMenu = ref(false);
function toggle(event: Event) {
  expanded.value = (event.target as HTMLDetailsElement).open;
  emit("expansion", { path: path.value, open: expanded.value });
}
const path = computed(() => props.path || "");
const matched = computed(() => props.matchPaths?.includes(path.value));
const containsMatch = computed(() =>
  props.matchPaths?.some(
    (item) => item === path.value || item.startsWith(path.value + "/"),
  ),
);
const expanded = ref((props.depth || 0) < 1);
const limit = ref<number>(JSON_CHILD_PAGE_SIZE);
watch(
  () => props.value,
  () => {
    limit.value = JSON_CHILD_PAGE_SIZE;
    expanded.value =
      props.expansionState?.[path.value] ??
      ((props.depth || 0) < 1 || !!containsMatch.value);
  },
  { immediate: true },
);
watch(containsMatch, (value) => {
  if (value) expanded.value = true;
});
watch(
  () => props.expansionState?.[path.value],
  (value) => {
    if (typeof value === "boolean") expanded.value = value;
  },
);
const objectKeys = computed(() =>
  props.value !== null &&
  typeof props.value === "object" &&
  !Array.isArray(props.value)
    ? Object.keys(props.value)
    : [],
);
const count = computed(() =>
  Array.isArray(props.value) ? props.value.length : objectKeys.value.length,
);
const summary = computed(() =>
  Array.isArray(props.value) ? `[${count.value}]` : "{" + count.value + "}",
);
const visibleKeys = computed(() =>
  expanded.value && props.value !== null && typeof props.value === "object"
    ? visibleJsonKeys(
        props.value,
        path.value,
        limit.value,
        props.matchPaths,
        objectKeys.value,
      )
    : [],
);
</script>
<template>
  <details
    v-if="value !== null && typeof value === 'object'"
    :open="expanded"
    @toggle="toggle"
    class="json-tree"
    :class="{
      'json-match': matched,
      'json-focused': focusedPath === path,
    }"
    :data-json-path="path"
  >
    <summary>
      <span class="json-key" @click.stop="emit('focus', path)">{{
        label || "消息体"
      }}</span>
      <span class="muted">{{ summary }}</span>
    </summary>
    <JsonTree
      v-for="key in visibleKeys"
      :key="key"
      :label="key"
      :value="(value as Record<string, unknown>)[key]"
      :depth="(depth || 0) + 1"
      :path="pointerChild(path, key)"
      :match-paths="matchPaths"
      :focused-path="focusedPath"
      :expansion-state="expansionState"
      :annotations="annotations"
      :field-actions="fieldActions"
      @expansion="emit('expansion', $event)"
      @column="emit('column', $event)"
      @note="emit('note', $event)"
      @watch-field="emit('watchField', $event)"
      @focus="emit('focus', $event)"
    />
    <button
      v-if="expanded && count > limit"
      class="json-more"
      @click="limit += JSON_CHILD_PAGE_SIZE"
    >
      继续展开 {{ JSON_CHILD_PAGE_SIZE }} 项（还有 {{ count - limit }} 项）
    </button>
  </details>
  <div
    v-else
    class="json-leaf"
    :class="{
      'json-match': matched,
      'json-focused': focusedPath === path,
    }"
    :data-json-path="path"
    @contextmenu.prevent="fieldMenu = !!fieldActions"
  >
    <span class="json-key" @click.stop="emit('focus', path)">{{ label }}</span
    >:
    <JsonScalar :value="value" />
    <span
      v-if="annotations?.[path]"
      class="field-note"
      :title="annotations[path]"
      >✎ {{ annotations[path] }}</span
    >
    <el-popover
      v-if="fieldActions"
      v-model:visible="fieldMenu"
      trigger="click"
      placement="bottom"
      :width="190"
    >
      <template #reference
        ><button class="json-value-action" title="字段操作" @click.stop>
          ⋯
        </button></template
      >
      <p class="muted research-path">{{ path }}</p>
      <el-button
        text
        @click="
          emit('column', { path, label: label || path });
          fieldMenu = false;
        "
        >添加到列表列</el-button
      >
      <el-button
        text
        @click="
          emit('note', path);
          fieldMenu = false;
        "
        >添加 / 编辑批注</el-button
      >
      <el-button
        text
        @click="
          emit('watchField', { path, label: label || path });
          fieldMenu = false;
        "
        >关注字段变化</el-button
      >
    </el-popover>
  </div>
</template>
