<script setup lang="ts">
import { ref, watch } from "vue";
import type { WorkspaceQuery } from "./workspaceInteractions";
import type { Mark, Connection } from "./types";
const props = defineProps<{
  item: { key: string; label: string; value?: string };
  query: WorkspaceQuery;
  marks?: Mark[];
  connections?: Connection[];
}>();
const emit = defineEmits<{
  remove: [];
  apply: [patch: Partial<WorkspaceQuery>];
}>();
const open = ref(false),
  draft = ref("");
const error = ref("");
const states: Record<string, string> = {
  DECODED: "已解码",
  PARTIAL: "部分解码",
  FAILED: "解码失败",
  UNMAPPED: "未映射",
  ATTENTION: "需关注",
};
const field = () =>
  (({ search: "q", heartbeat: "hideHeartbeat" })[props.item.key] ||
    props.item.key) as keyof WorkspaceQuery;
watch(open, (value) => {
  error.value = "";
  if (value)
    draft.value = props.item.value || String(props.query[field()] ?? "");
});
function apply() {
  const key = field();
  let value: string | boolean = draft.value.trim();
  if (key === "hideHeartbeat") value = draft.value === "true";
  if (
    (key === "protocolId" || key === "messageIndex") &&
    value &&
    !/^\d+$/.test(String(value))
  ) {
    error.value = "请输入非负整数编号";
    return;
  }
  if ((key === "include" || key === "exclude") && props.item.value) {
    const values = props.query[key]
      .split(/[,，\s]+/)
      .filter(Boolean)
      .filter((token) => token !== props.item.value);
    if (value) values.push(String(value));
    value = values.join(",");
  }
  emit("apply", { [key]: value });
  open.value = false;
}
</script>
<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="300"
    ><template #reference
      ><el-tag
        closable
        disable-transitions
        class="editable-filter"
        :title="`${item.label}；点击修改`"
        @close.stop="emit('remove')"
        ><button @click.stop="open = !open" :aria-label="`修改${item.label}`">
          {{ item.label }}
        </button></el-tag
      ></template
    ><el-form label-position="top" @submit.prevent="apply"
      ><el-form-item :label="item.label"
        ><el-select
          v-if="item.key === 'direction'"
          v-model="draft"
          aria-label="修改方向"
          ><el-option label="全部方向" value="" /><el-option
            label="发送 CS"
            value="CS" /><el-option label="接收 SC" value="SC" /></el-select
        ><el-select v-else-if="item.key === 'heartbeat'" v-model="draft"
          ><el-option label="隐藏心跳" value="true" /><el-option
            label="显示心跳"
            value="false" /></el-select
        ><el-select v-else-if="item.key === 'status'" v-model="draft"
          ><el-option label="全部状态" value="" /><el-option
            v-for="status in [
              'DECODED',
              'PARTIAL',
              'FAILED',
              'UNMAPPED',
              'ATTENTION',
            ]"
            :key="status"
            :label="states[status]"
            :value="status" /></el-select
        ><el-select
          v-else-if="item.key === 'range'"
          v-model="draft"
          aria-label="修改区间"
          ><el-option label="全部区间" value="" /><el-option
            v-for="(mark, index) in marks || []"
            :key="index"
            :label="`${mark.name} · #${mark.from}–${mark.to}`"
            :value="String(index + 1)" /></el-select
        ><el-select
          v-else-if="item.key === 'connection'"
          v-model="draft"
          aria-label="修改连接"
          ><el-option label="全部连接" value="" /><el-option
            v-for="connection in connections || []"
            :key="connection.id"
            :label="
              connection.note || connection.identity?.nickname || connection.id
            "
            :value="connection.id" /></el-select
        ><el-input
          v-else
          v-model="draft"
          aria-label="修改筛选值"
          @keyup.enter="apply"
      /></el-form-item>
      <p v-if="error" class="warning-bar">{{ error }}</p>
      <el-button size="small" @click="open = false">取消</el-button
      ><el-button size="small" type="primary" @click="apply"
        >应用</el-button
      ></el-form
    ></el-popover
  >
</template>
