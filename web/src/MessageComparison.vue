<script setup lang="ts">
import { computed } from "vue";
import { compareFields } from "./analysisWorkspace";
import type { Packet } from "./types";
const props = defineProps<{ before: Packet; after: Packet }>();
const result = computed(() =>
  compareFields(props.before.body, props.after.body),
);
</script>
<template>
  <div class="comparison-content">
    <p>
      基准：消息 #{{ before.index }} · {{ before.protocolName }} · 协议 ID
      {{ before.protocolId ?? "未知" }}
    </p>
    <p>
      当前：消息 #{{ after.index }} · {{ after.protocolName }} · 协议 ID
      {{ after.protocolId ?? "未知" }}
    </p>
    <p
      v-if="
        before.protocolId !== after.protocolId ||
        before.direction !== after.direction
      "
      class="warning-bar"
    >
      两条消息的协议或方向不同，请结合各自结构理解差异。
    </p>
    <p class="muted">
      仅对比消息体；数组按位置比较，不自动推断业务对象、请求响应或广播关系。
    </p>
    <p v-if="result.truncated" class="warning-bar">
      对比已达扫描或展示上限，仅展示已检查范围内的变化，不能据此判断完整消息相同。
    </p>
    <el-table
      :data="result.changes"
      max-height="480"
      empty-text="已检查范围内没有字段变化"
    >
      <el-table-column
        prop="path"
        label="字段路径（JSON Pointer）"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column prop="kind" label="变化" width="72" />
      <el-table-column
        prop="before"
        label="基准值"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        prop="after"
        label="当前值"
        min-width="200"
        show-overflow-tooltip
      />
    </el-table>
  </div>
</template>
