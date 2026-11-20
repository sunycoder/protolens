<script setup lang="ts">
import type { useSavedViews } from "./useSavedViews";
const props = defineProps<{
  controller: ReturnType<typeof useSavedViews>;
  canSave: boolean;
  canApply: boolean;
}>();
const { savedViews, viewDialog, saveView, applyView, deleteView } =
  props.controller;
</script>
<template>
  <el-dialog v-model="viewDialog" title="常用分析视图" width="min(600px,94vw)">
    <p class="muted">
      保存已生效筛选和显示列；不保存消息、连接、区间或未提交输入。最多 12
      个，同名覆盖。
    </p>
    <el-button :disabled="!canSave" @click="saveView">保存当前视图</el-button>
    <p v-if="!savedViews.length" class="muted">尚未保存视图</p>
    <div v-for="view in savedViews" :key="view.name" class="saved-view-row">
      <strong>{{ view.name }}</strong
      ><span class="muted"
        >字典 {{ view.dictionary?.slice(0, 12) || "未知" }}</span
      >
      <el-button :disabled="!canApply" @click="applyView(view)">应用</el-button
      ><el-button text @click="deleteView(view.name)">删除</el-button>
    </div>
  </el-dialog>
</template>
