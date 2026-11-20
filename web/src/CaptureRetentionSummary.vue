<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { api, type Capture } from "./types";
import type { CaptureActivity } from "./liveReading";
const props = defineProps<{
  capture: Capture;
  summary?: Capture;
  latest?: CaptureActivity;
}>();
const capture = computed(() => props.capture);
const retentionSnapshot = shallowRef<Capture>();
const retentionError = ref("");
async function refreshRetention() {
  const id = capture.value?.id;
  if (!id) return;
  retentionError.value = "";
  try {
    const result = await api<Capture>(`/api/captures/${id}/overview`);
    if (capture.value?.id === id) retentionSnapshot.value = result;
  } catch {
    if (capture.value?.id === id)
      retentionError.value = "保存范围刷新失败，以下保留上次确认结果";
  }
}
const retentionCapture = computed(() => {
  const record = capture.value;
  if (!record) return;
  const summary = props.summary || record;
  const latest = props.latest;
  const confirmed =
    retentionSnapshot.value?.id === record.id
      ? retentionSnapshot.value
      : record;
  return {
    ...record,
    ...confirmed,
    messageCount: summary.messageCount,
    evictedCount: summary.evictedCount,
    firstIndex:
      latest?.captureId === record.id
        ? latest.firstRetained
        : summary.firstIndex,
    lastIndex:
      latest?.captureId === record.id ? latest.lastIndex : summary.lastIndex,
  };
});
</script>
<template>
  <el-popover trigger="click" :width="340" @show="refreshRetention"
    ><template #reference
      ><button class="storage-summary">
        {{ retentionCapture?.temporary ? "临时记录" : "已保存" }} · 内存
        {{ retentionCapture?.messageCount.toLocaleString() }} 条{{
          retentionCapture?.continuous ? " · 持续保存中" : ""
        }}
      </button></template
    >
    <p>
      内存保留 #{{ retentionCapture?.firstIndex ?? 0 }}–{{
        retentionCapture?.lastIndex ?? 0
      }}，上限 {{ retentionCapture?.maxMessages ?? "未提供" }} 条。
    </p>
    <p v-if="retentionError" class="warning-bar">
      {{ retentionError }}
    </p>
    <p v-if="retentionCapture?.diskFirstIndex !== undefined">
      磁盘已保存 #{{ retentionCapture.diskFirstIndex }}–{{
        retentionCapture.diskLastIndex
      }}。
    </p>
    <p v-else>
      {{
        retentionCapture?.temporary
          ? "当前记录尚未保存，退出工作台会丢失临时内容。"
          : "当前为已保存记录。"
      }}
    </p>
    <p v-if="retentionCapture?.evictedCount">
      内存已淘汰 {{ retentionCapture.evictedCount }} 条；{{
        retentionCapture.diskFirstIndex === undefined
          ? "更早消息未保存，无法恢复。"
          : "请按磁盘已保存范围核对是否可恢复。"
      }}
    </p>
    <p v-if="capture?.clearedCount">
      手动清空 {{ capture?.clearedCount }} 条。
    </p></el-popover
  >
</template>
