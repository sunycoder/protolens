<script setup lang="ts">
import type { Capture } from "./types";
import type { useCaptureOperations } from "./useCaptureOperations";
import {
  MAX_CAPTURE_NAME_LENGTH,
  MAX_OPERATION_CONTEXT_LENGTH,
} from "./constants";
const props = defineProps<{
  controller: ReturnType<typeof useCaptureOperations>;
  capture?: Capture;
  supportsAnalysis: boolean;
  busy: boolean;
}>();
const { operation, markDialog, lastOperationRange, freezeOperation, mark } =
  props.controller;
const emit = defineEmits<{ review: [] }>();
const openResearch = (_mode?: string) => emit("review");
</script>
<template>
  <el-dialog
    v-model="markDialog"
    title="标记区间 / 保存 AI 分析快照"
    width="500px"
    ><p class="muted">
      以列表“消息序号”列为边界，保存一次操作前后的消息范围。区间不自动证明请求与推送的因果关系。“保存分析快照”会复制并保存该区间和字典，原连接继续采集；已淘汰的消息无法补回。
    </p>
    <el-form label-width="90px"
      ><el-form-item label="操作名称"
        ><el-input
          v-model="operation.name"
          :maxlength="MAX_CAPTURE_NAME_LENGTH"
          placeholder="例如：联盟科技捐献一次" /></el-form-item
      ><el-form-item label="操作背景"
        ><el-input
          v-model="operation.context"
          type="textarea"
          :rows="2"
          :maxlength="MAX_OPERATION_CONTEXT_LENGTH"
          placeholder="操作前状态、执行动作和想确认的问题（随快照保存）" /></el-form-item
      ><el-form-item label="起始序号 #"
        ><el-input-number
          v-model="operation.from"
          :min="capture?.firstIndex || 0"
          :max="
            capture?.lastIndex ?? Math.max(0, (capture?.messageCount || 1) - 1)
          " /></el-form-item
      ><el-form-item label="结束序号 #"
        ><el-input-number
          v-model="operation.to"
          :min="operation.from"
          :max="
            capture?.lastIndex ?? Math.max(0, (capture?.messageCount || 1) - 1)
          " /></el-form-item></el-form
    ><template #footer
      ><el-button @click="markDialog = false">取消</el-button
      ><el-button
        v-if="supportsAnalysis"
        @click="
          lastOperationRange = { from: operation.from, to: operation.to };
          markDialog = false;
          openResearch('review');
        "
        >查看操作回顾</el-button
      ><el-button type="primary" :loading="busy" @click="freezeOperation"
        >保存快照并分析</el-button
      ><el-button type="primary" :loading="busy" @click="mark"
        >保存区间</el-button
      ></template
    ></el-dialog
  >
</template>
