<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { readStored, writeStored } from "./workspaceSession";
import { normalizedSearches } from "./workspaceNavigation";
const props = defineProps<{ modelValue: string; dictionary: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  search: [];
}>();
const open = ref(false),
  items = ref<{ q: string; pinned: boolean }[]>([]);
watch(
  () => props.dictionary,
  () => {
    items.value = normalizedSearches(
      readStored<Record<string, unknown>>("analyzer.searches.v1", {})[
        props.dictionary
      ],
    );
    open.value = false;
  },
  { immediate: true },
);
const ordered = computed(() =>
  [...items.value].sort((a, b) => Number(b.pinned) - Number(a.pinned)),
);
function save() {
  const all = readStored<Record<string, unknown>>("analyzer.searches.v1", {});
  writeStored(
    "analyzer.searches.v1",
    Object.fromEntries(
      [
        ...Object.entries(all && typeof all === "object" ? all : {}).filter(
          ([key]) => key !== props.dictionary,
        ),
        [props.dictionary, items.value],
      ].slice(-12),
    ),
  );
}
function record() {
  const q = props.modelValue.trim();
  if (q && q.length <= 300) {
    const previous = items.value.find((item) => item.q === q);
    items.value = [
      { q, pinned: previous?.pinned || false },
      ...items.value.filter((item) => item.q !== q),
    ].slice(0, 20);
    save();
  }
  open.value = false;
}
function remember() {
  record();
  emit("search");
}
function use(q: string) {
  emit("update:modelValue", q);
  open.value = false;
  emit("search");
}
function pin(q: string) {
  const item = items.value.find((item) => item.q === q);
  if (item) {
    item.pinned = !item.pinned;
    save();
  }
}
defineExpose({ record });
</script>
<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="420"
    ><template #reference
      ><el-input
        :model-value="modelValue"
        @update:model-value="emit('update:modelValue', $event)"
        clearable
        aria-label="搜索协议或字段"
        placeholder="协议名、字段名或实际值"
        @keyup.enter="remember"
        @clear="emit('search')"
    /></template>
    <div class="search-history">
      <b>当前字典的最近搜索</b>
      <p v-if="!ordered.length" class="muted">输入搜索后按回车，或点击查询。</p>
      <div v-for="item in ordered" :key="item.q" class="search-history-row">
        <el-button
          text
          class="history-query"
          :title="item.q"
          @click="use(item.q)"
          >{{ item.q }}</el-button
        ><el-button
          text
          :aria-label="`${item.pinned ? '取消固定' : '固定'}搜索 ${item.q}`"
          @click="pin(item.q)"
          >{{ item.pinned ? "已固定" : "固定" }}</el-button
        ><el-button
          text
          type="danger"
          :aria-label="`删除搜索 ${item.q}`"
          @click="
            items = items.filter((value) => value.q !== item.q);
            save();
          "
          >删除</el-button
        >
      </div>
    </div></el-popover
  >
</template>
