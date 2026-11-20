<script setup lang="ts">
import type { Capture } from "./types";
import type { CaptureActivity } from "./liveReading";
import type { useAnalysisTabs } from "./useAnalysisTabs";
import type { useProjectSync } from "./useProjectSync";
import { MAX_ANALYSIS_TABS } from "./analysisTools";
import CaptureRetentionSummary from "./CaptureRetentionSummary.vue";
const props = defineProps<{
  capture: Capture;
  summary?: Capture;
  latest?: CaptureActivity;
  tabs: ReturnType<typeof useAnalysisTabs>;
  projectSync: ReturnType<typeof useProjectSync>;
  analysisProjects: boolean;
  actions: {
    switchAnalysisTab: (id: number) => Promise<void>;
    closeAnalysisTab: (id: number) => Promise<void>;
    renameAnalysisTab: (id: number) => Promise<void>;
    newAnalysisTab: () => void;
    confirmReloadProject: () => Promise<void>;
  };
}>();
const { tabs: analysisTabs, active: activeAnalysisTab } = props.tabs;
const {
  switchAnalysisTab,
  closeAnalysisTab,
  renameAnalysisTab,
  newAnalysisTab,
  confirmReloadProject,
} = props.actions;
</script>
<template>
  <div class="analysis-tabbar" aria-label="临时分析页签">
    <span v-if="analysisProjects" class="project-save-state" role="status">{{
      projectSync.error.value
        ? "分析成果未保存"
        : projectSync.pending.value || projectSync.saving.value
          ? "正在保存分析成果"
          : projectSync.ready.value
            ? "分析成果随记录保存"
            : "加载分析成果中"
    }}</span
    ><el-button
      v-if="projectSync.error.value"
      text
      size="small"
      :title="projectSync.error.value"
      @click="confirmReloadProject"
      >重新加载成果</el-button
    >
    <span
      v-for="item in analysisTabs"
      :key="item.id"
      class="analysis-tab"
      :class="{ active: item.id === activeAnalysisTab }"
      ><button
        @click="switchAnalysisTab(item.id)"
        @dblclick="renameAnalysisTab(item.id)"
        :aria-pressed="item.id === activeAnalysisTab"
        title="双击重命名"
      >
        {{ item.name }}</button
      ><button
        v-if="analysisTabs.length > 1"
        @click="closeAnalysisTab(item.id)"
        :aria-label="`关闭 ${item.name}`"
      >
        ×
      </button></span
    ><el-button
      text
      size="small"
      @click="newAnalysisTab"
      :disabled="analysisTabs.length >= MAX_ANALYSIS_TABS"
      >＋ 分析页签</el-button
    >
    <CaptureRetentionSummary
      :capture="capture"
      :summary="summary"
      :latest="latest"
    />
  </div>
</template>
