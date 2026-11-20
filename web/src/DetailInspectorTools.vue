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
  position = ref(-1);
const result = computed(() => findFieldMatches(props.body, query.value));
const trail = computed(() => pointerTrail(props.focusedPath));
watch(
  () => props.messageKey,
  () => {
    query.value = "";
    position.value = -1;
    emit("search", "");
  },
);
watch(query, (value) => {
  position.value = -1;
  emit("search", value);
});
function move(delta: number) {
  const matches = result.value.matches;
  if (!matches.length) return;
  position.value =
    position.value < 0
      ? delta > 0
        ? 0
        : matches.length - 1
      : (position.value + delta + matches.length) % matches.length;
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
      /><span class="muted detail-match-count" role="status" aria-live="polite"
        >{{
          result.matches.length
            ? position < 0
              ? `${result.matches.length} 处`
              : `${position + 1}/${result.matches.length}`
            : query
              ? "无匹配"
              : ""
        }}{{ result.truncated ? "（已截断）" : "" }}</span
      ><el-button
        size="small"
        text
        :disabled="!result.matches.length"
        aria-label="上一处匹配"
        title="上一处匹配"
        @click="move(-1)"
        >‹</el-button
      ><el-button
        size="small"
        text
        :disabled="!result.matches.length"
        aria-label="下一处匹配"
        title="下一处匹配"
        @click="move(1)"
        >›</el-button
      >
    </div>
    <el-button size="small" @click="emit('maximize')">{{
      maximized ? "恢复分栏" : "放大详情"
    }}</el-button>
    <nav v-if="focusedPath" class="field-breadcrumbs" aria-label="当前字段路径">
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
