<script setup lang="ts">
import type { useResearchWorkspace } from "./useResearchWorkspace";
const props = defineProps<{
  controller: ReturnType<typeof useResearchWorkspace>;
}>();
const { noteDialog, noteDraft, saveNote } = props.controller;
</script>
<template>
  <el-dialog
    v-model="noteDialog"
    title="消息 / 字段批注"
    width="min(520px, 94vw)"
    ><p>消息 #{{ noteDraft.index }} · {{ noteDraft.path || "整条消息" }}</p>
    <p class="muted">
      批注为人工记录，不改变协议解码结果。标为“已验证”须由你自行核对证据。
    </p>
    <el-select v-model="noteDraft.kind" aria-label="批注类型"
      ><el-option
        v-for="kind in ['备注', '推测', '已验证']"
        :key="kind"
        :label="kind"
        :value="kind" /></el-select
    ><el-input
      v-model="noteDraft.text"
      type="textarea"
      :rows="4"
      maxlength="500"
      show-word-limit
      placeholder="记录观察、判断依据或待确认的问题"
    /><template #footer
      ><el-button @click="noteDialog = false">取消</el-button
      ><el-button type="primary" @click="saveNote"
        >保存批注</el-button
      ></template
    ></el-dialog
  >
</template>
