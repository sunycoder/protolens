<script setup lang="ts">
import { ref } from "vue";
import type { Capture } from "./types";
import type { useWorkbenchSettings } from "./useWorkbenchSettings";
import AiAnalysisPanel from "./AiAnalysisPanel.vue";
const props = defineProps<{
  controller: ReturnType<typeof useWorkbenchSettings>;
  health: { ready: boolean; catalog: { messageCount: number } };
  capture?: Capture;
  selectedRowIndex?: number;
  mainWorkspace: "captures" | "catalog";
}>();
const dark = defineModel<boolean>("dark", { required: true });
const emit = defineEmits<{ captures: []; catalog: [] }>();
const { settings, update, openSettings, checkUpdate } = props.controller;
const importGuideDialog = ref(false);
const returnCaptureWorkspace = () => emit("captures"),
  openProtocolQuery = () => emit("catalog");
</script>
<template>
  <header class="topbar">
    <div class="brand">
      <span class="brand-icon">⇄</span>
      <div>ProtoLens<small>游戏协议分析工作台</small></div>
    </div>
    <span class="divider"></span>
    <span class="state-dot" :class="{ online: health.ready }"></span
    ><span class="muted">{{
      health.ready ? "本机服务已连接" : "连接后端中"
    }}</span>
    <el-tag size="small" effect="plain">Thrift Compact</el-tag>
    <el-button
      text
      :type="mainWorkspace === 'captures' ? 'primary' : 'default'"
      @click="returnCaptureWorkspace"
      >抓包分析</el-button
    >
    <el-button
      text
      :type="mainWorkspace === 'catalog' ? 'primary' : 'default'"
      @click="openProtocolQuery()"
      >协议查询 ·
      {{ health.catalog.messageCount.toLocaleString() }} 条</el-button
    >
    <span v-if="settings.serverPort" class="muted" :title="settings.configFile"
      >后端 :{{ settings.serverPort }} · 页面 :{{ settings.frontendPort }} ·
      代理 :{{ settings.collectorPort }}</span
    >
    <div class="topbar-right">
      <AiAnalysisPanel :capture="capture" :selected-index="selectedRowIndex" />
      <el-button text @click="openSettings">设置 / 协议加载</el-button>
      <el-dropdown trigger="click"
        ><el-button text>更多</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item @click="checkUpdate">检查更新</el-dropdown-item
            ><el-dropdown-item @click="importGuideDialog = true"
              >SAZ 导入说明</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
      <!-- 安装包交给浏览器原生下载；fetch 会被同源 CSP 拦截，并额外受跨域读取限制。 -->
      <el-button
        v-if="update.available && update.downloadUrl"
        tag="a"
        text
        :href="update.downloadUrl"
        target="_blank"
        rel="noopener noreferrer"
        title="由浏览器下载；进度请查看浏览器下载列表，失败时可打开发版页面"
        >下载 {{ update.latestVersion }}</el-button
      >
      <el-button
        v-if="update.available && update.releaseUrl"
        tag="a"
        text
        :href="update.releaseUrl"
        target="_blank"
        rel="noopener noreferrer"
        >发版页面</el-button
      >
      <el-switch
        v-model="dark"
        inline-prompt
        active-text="深色"
        inactive-text="浅色"
      />
    </div>
  </header>
  <el-dialog
    v-model="importGuideDialog"
    title="SAZ 导入说明"
    width="min(600px, 94vw)"
  >
    <p class="muted">用于分析已有抓包文件。实时采集请使用顶部的“接入步骤”。</p>
    <ol class="guide">
      <li>
        准备 .saz 文件，需包含从握手开始的完整主游戏 WebSocket
        会话。已有文件可直接导入，无需为导入开启系统代理。
      </li>
      <li>在“设置 / 协议加载”中加载与抓包版本匹配的客户端协议字典。</li>
      <li>
        打开“记录 / 导入”，点击“选择 SAZ
        文件”；也可填写当前运行工作台的这台电脑上的文件绝对路径，再点击“从路径导入”。
      </li>
      <li>
        导入完成后选择该记录，按协议名、协议 ID
        或消息序号查找，查看消息字段与原始包体。
      </li>
    </ol>
    <p class="muted">
      Windows、macOS、Linux
      均使用同一导入入口。旧记录保留导入时的字典；要使用新字典重新解析，请重新导入原文件。
    </p>
  </el-dialog>
</template>
