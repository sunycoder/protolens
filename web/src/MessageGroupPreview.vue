<script setup lang="ts">
import type { Packet } from "./types";
defineProps<{ rows: Packet[]; protocol: boolean; expanded: boolean }>();
const emit = defineEmits<{ close: []; inspect: [row: Packet]; expand: [] }>();
function inspect(row: Packet) {
  emit("inspect", row);
}
</script>
<template>
  <el-dialog
    :model-value="true"
    title="本页消息组预览"
    width="min(680px, 94vw)"
    @close="emit('close')"
    ><p class="muted">
      {{
        protocol
          ? "同协议归组，内容可不同；穿插的其他协议仍保留。"
          : "相同业务内容的连续消息。"
      }}点击成员查看固定详情，原列表位置保持。
    </p>
    <el-table :data="rows" max-height="280" size="small" @row-click="inspect"
      ><el-table-column
        prop="index"
        label="原编号"
        width="80"
      /><el-table-column prop="time" label="时间" width="120" /><el-table-column
        prop="preview"
        label="摘要"
        min-width="220"
        show-overflow-tooltip
      /><el-table-column label="详情" width="70"
        ><template #default="{ row }"
          ><el-button text size="small" @click.stop="emit('inspect', row)"
            >查看</el-button
          ></template
        ></el-table-column
      ></el-table
    ><template #footer
      ><el-button @click="emit('close')">关闭预览</el-button
      ><el-button type="primary" @click="emit('expand')">{{
        expanded ? "收起列表中的整组" : "在列表展开整组"
      }}</el-button></template
    ></el-dialog
  >
</template>
