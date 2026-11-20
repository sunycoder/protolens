<script setup lang="ts">
import type { Capture, Packet } from "./types";
import type { useCaptureOperations } from "./useCaptureOperations";
import type { useAnalysisProject } from "./useAnalysisProject";
import type { useMessageSelection } from "./useMessageSelection";
import { captureName } from "./capturePresentation";
const props = defineProps<{
  capture?: Capture;
  total: number;
  sidebarVisible: boolean;
  searchOrigin: boolean;
  contextAnchor?: Packet;
  contextScope: "connection" | "all";
  locatedRow?: number;
  isFollowing: boolean;
  pauseDescription: string;
  analysisProjects: boolean;
  operations: ReturnType<typeof useCaptureOperations>;
  project: ReturnType<typeof useAnalysisProject>;
  selection: ReturnType<typeof useMessageSelection>;
  actions: {
    openCapture: (capture: Capture) => Promise<void>;
    jumpToContext: (
      packet: Packet,
      scope?: "connection" | "all",
    ) => Promise<void>;
    returnToSearch: () => Promise<void>;
    saveCapture: () => Promise<void>;
  };
}>();
const sidebarCollapsed = defineModel<boolean>("sidebarCollapsed", {
    required: true,
  }),
  focusMode = defineModel<boolean>("focusMode", { required: true });
const connectionIdentityDialog = defineModel<boolean>(
  "connectionIdentityDialog",
  { required: true },
);
const workspaceView = defineModel<"messages" | "protocols">("workspaceView", {
  required: true,
});
const { selected, selectedRowIndex, detailOpen } = props.selection;
const {
  continuousBusy,
  snapshotBusy,
  savedSnapshot,
  operationStart,
  enableContinuous,
  saveCurrentSnapshot,
  beginOperation,
  endOperation,
  openMark,
  exportCapture,
} = props.operations;
const { projectBusy, projectInput, exportAnalysisProject } = props.project;
const { openCapture, jumpToContext, returnToSearch, saveCapture } =
  props.actions;
</script>
<template>
  <div class="capture-bar">
    <div class="capture-identity">
      <el-button
        size="small"
        @click="sidebarCollapsed = !sidebarCollapsed"
        :aria-expanded="sidebarVisible"
        >{{ sidebarVisible ? "收起记录" : "记录 / 导入" }}</el-button
      >
      <strong :title="capture?.name">{{
        capture ? captureName(capture) : "选择或导入抓包"
      }}</strong>
      <el-radio-group
        v-model="workspaceView"
        size="small"
        aria-label="数据视图"
      >
        <el-radio-button value="messages">消息</el-radio-button>
        <el-radio-button value="protocols" :disabled="!capture"
          >协议概览</el-radio-button
        >
      </el-radio-group>
      <span
        v-if="capture"
        class="muted retention-state"
        :title="`已清空 ${capture.clearedCount || 0} 条；消息编号不重用`"
      >
        {{ capture.temporary ? "临时" : "已保存" }} · #{{
          capture.firstIndex ?? 0
        }}–{{ capture.lastIndex ?? 0 }}
        <span v-if="capture.evictedCount">
          · 内存淘汰 {{ capture.evictedCount }}</span
        >
        <span v-if="capture.diskFirstIndex !== undefined">
          · 磁盘 #{{ capture.diskFirstIndex }}–{{ capture.diskLastIndex }}</span
        >
      </span>
      <el-popover trigger="hover" :width="300" placement="bottom-start">
        <template #reference
          ><span class="capture-count"
            >{{ total.toLocaleString() }} /
            {{ (capture?.messageCount || 0).toLocaleString() }} 条</span
          ></template
        >
        <p>
          当前列表匹配 {{ total }} 条，记录保留
          {{ capture?.messageCount || 0 }} 条。
        </p>
        <p>
          已解码 {{ capture?.counts.DECODED || 0 }} · 部分
          {{ capture?.counts.PARTIAL || 0 }} · 未映射
          {{ capture?.counts.UNMAPPED || 0 }} · 失败
          {{ capture?.counts.FAILED || 0 }}
        </p>
        <p v-if="capture?.evictedCount">
          已淘汰 {{ capture.evictedCount }} 条旧消息，消息序号保留原值。
        </p>
      </el-popover>
      <el-tag v-if="searchOrigin" size="small" :title="selected?.protocolName"
        >上下文 #{{ locatedRow ?? selectedRowIndex }}</el-tag
      >
      <span
        v-else-if="capture?.liveState === 'CAPTURING'"
        class="reading-hint"
        title="后台采集继续，新消息不会改变当前位置"
        >正在采集 · {{ isFollowing ? "跟随最新" : pauseDescription }}</span
      >
    </div>
    <div class="capture-actions">
      <el-button
        v-if="capture?.liveState === 'CAPTURING' && !capture.continuous"
        size="small"
        :loading="continuousBusy"
        :disabled="!!capture.continuousError"
        @click="enableContinuous"
        title="从当前保留范围开始保存，并持续追加后续消息；不追补此前已淘汰内容"
        >持续保存</el-button
      >
      <span v-if="capture?.continuous" class="muted">正在持续保存</span>
      <el-button
        v-if="capture"
        size="small"
        :loading="snapshotBusy"
        @click="saveCurrentSnapshot"
        title="保存此刻仍保留的消息和字典，不自动保存后续消息"
        >保存此刻</el-button
      >
      <el-button
        v-if="savedSnapshot"
        text
        size="small"
        @click="openCapture(savedSnapshot)"
        >查看已存快照</el-button
      >
      <el-dropdown v-if="searchOrigin && contextAnchor" trigger="click"
        ><el-button size="small">{{
          contextScope === "connection" ? "当前连接上下文" : "全部连接上下文"
        }}</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item
              @click="
                contextAnchor && jumpToContext(contextAnchor, 'connection')
              "
              >当前连接上下文</el-dropdown-item
            ><el-dropdown-item
              @click="contextAnchor && jumpToContext(contextAnchor, 'all')"
              >全部连接上下文</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
      <el-button
        v-if="searchOrigin"
        size="small"
        type="primary"
        plain
        @click="returnToSearch"
        >返回搜索结果</el-button
      >
      <el-button
        v-if="
          capture &&
          (capture.temporary || capture.diskFirstIndex !== undefined) &&
          capture.liveState !== 'CAPTURING'
        "
        size="small"
        type="primary"
        @click="saveCapture"
        >保存记录</el-button
      >
      <el-button
        v-if="
          capture?.liveState === 'CAPTURING' &&
          operationStart?.captureId !== capture.id
        "
        size="small"
        @click="beginOperation"
        >开始标记操作</el-button
      >
      <el-button
        v-if="capture && operationStart?.captureId === capture.id"
        size="small"
        type="primary"
        @click="endOperation"
        >结束并选取区间</el-button
      >
      <el-dropdown trigger="click"
        ><el-button size="small" :disabled="!capture">记录操作</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item
              :disabled="!analysisProjects || projectBusy"
              @click="exportAnalysisProject"
              >导出分析项目（含批注与视图）</el-dropdown-item
            ><el-dropdown-item
              :disabled="!analysisProjects || projectBusy"
              @click="projectInput?.click()"
              >导入分析项目</el-dropdown-item
            ><el-dropdown-item @click="connectionIdentityDialog = true"
              >账号 / 备注</el-dropdown-item
            ><el-dropdown-item @click="openMark">标记操作区间</el-dropdown-item
            ><el-dropdown-item @click="exportCapture"
              >导出解码 JSON</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
      <el-button
        size="small"
        :disabled="!selected"
        @click="detailOpen = !detailOpen"
        >{{ detailOpen ? "收起详情" : "展开详情" }}</el-button
      >
      <el-button size="small" @click="focusMode = true">专注列表</el-button>
    </div>
  </div>
</template>
