<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { findFieldMatches } from "./analysisWorkspace";
import { pointerTrail } from "./workspaceNavigation";
const props = defineProps<{
  body: unknown;
  focusedPath: string;
  messageKey: string;
  maximized: boolean;
}>();
const emit = defineEmits<{
  focus: [path: string];
  search: [value: string];
  maximize: [];
}>();
const query = ref(""),
  position = ref(0);
const result = computed(() => findFieldMatches(props.body, query.value));
const trail = computed(() => pointerTrail(props.focusedPath));
watch(
  () => props.messageKey,
  () => {
    query.value = "";
    position.value = 0;
    emit("search", "");
  },
);
watch(query, (value) => {
  position.value = 0;
  emit("search", value);
});
function move(delta: number) {
  const matches = result.value.matches;
  if (!matches.length) return;
  position.value = (position.value + delta + matches.length) % matches.length;
  emit("focus", matches[position.value].path);
}
</script>
<template>
  <div class="detail-inspector-tools">
    <div class="detail-local-search">
      <el-input
        v-model="query"
        size="small"
        clearable
        placeholder="仅搜索当前消息字段或值"
        aria-label="搜索当前消息"
        @keyup.enter="move(1)"
      /><span class="muted"
        >{{
          result.matches.length
            ? `${position + 1}/${result.matches.length}`
            : "0 处"
        }}{{ result.truncated ? "（已截断）" : "" }}</span
      ><el-button
        size="small"
        text
        :disabled="!result.matches.length"
        @click="move(-1)"
        >上一处</el-button
      ><el-button
        size="small"
        text
        :disabled="!result.matches.length"
        @click="move(1)"
        >下一处</el-button
      >
    </div>
    <el-button size="small" @click="emit('maximize')">{{
      maximized ? "恢复分栏" : "放大详情"
    }}</el-button>
    <nav class="field-breadcrumbs" aria-label="当前字段路径">
      <template v-for="(item, index) in trail" :key="item.path"
        ><span v-if="index" class="muted">›</span
        ><button
          :title="item.path || '消息体'"
          @click="emit('focus', item.path)"
        >
          {{ item.label }}
        </button></template
      >
    </nav>
  </div>
</template>
