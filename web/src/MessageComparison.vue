<script setup lang="ts">
import { computed, ref } from "vue";
import { compareFields } from "./analysisWorkspace";
import type { Packet } from "./types";
const props = defineProps<{ before: Packet; after: Packet }>();
const arrayKey = ref("");
const appliedArrayKey = ref("");
const result = computed(() =>
  compareFields(props.before.body, props.after.body, appliedArrayKey.value),
);
</script>
<template>
  <div class="comparison-content">
    <p>
      基准：消息 #{{ before.index }} · {{ before.protocolName }} · 协议 ID
      {{ before.protocolId ?? "未知" }}
      · {{ before.direction }} · 连接 {{ before.connection }}
    </p>
    <p>
      当前：消息 #{{ after.index }} · {{ after.protocolName }} · 协议 ID
      {{ after.protocolId ?? "未知" }}
      · {{ after.direction }} · 连接 {{ after.connection }}
    </p>
    <p
      v-if="
        before.protocolId !== after.protocolId ||
        before.direction !== after.direction ||
        before.connection !== after.connection
      "
      class="warning-bar"
    >
      两条消息的协议、方向或连接不同，请结合各自结构理解差异。
    </p>
    <p class="muted">
      只展示变化字段；数组{{
        appliedArrayKey
          ? `按 ${appliedArrayKey} 对齐（路径显示业务键）`
          : "按位置比较（JSON Pointer）"
      }}。不自动推断请求响应或广播关系。
    </p>
    <div class="overview-tools">
      <el-input
        v-model="arrayKey"
        placeholder="数组对齐字段，如 id、heroId"
        style="width: 240px"
        clearable
        @keyup.enter="appliedArrayKey = arrayKey.trim()"
      />
      <el-button @click="appliedArrayKey = arrayKey.trim()"
        >应用对齐规则</el-button
      >
    </div>
    <p v-for="warning in result.warnings" :key="warning" class="warning-bar">
      {{ warning }}
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
        :label="
          appliedArrayKey
            ? '字段路径（数组含业务键）'
            : '字段路径（JSON Pointer）'
        "
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
