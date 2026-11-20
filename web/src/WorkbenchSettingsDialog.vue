<script setup lang="ts">
import type { useWorkbenchSettings } from "./useWorkbenchSettings";
import { CACHE_BUDGET } from "./constants";
import CollectorSettingsPanel from "./CollectorSettingsPanel.vue";
const props = defineProps<{
  controller: ReturnType<typeof useWorkbenchSettings>;
  catalogCount: number;
}>();
const {
  settingsDialog,
  settingsTab,
  settingsBusy,
  update,
  settings,
  protocolCandidates,
  selectedProtocolFile,
  sourceChanged,
  findProtocolFiles,
  openSettings,
  checkUpdate,
  saveSettings,
  reloadCatalog,
} = props.controller;
</script>
<template>
  <el-dialog v-model="settingsDialog" title="采集设置与竞品协议" width="680px">
    <p class="muted">
      修改前请停止采集。路径和缓存设置保存在本机，启动时自动加载；历史记录保留各自的协议字典。
    </p>
    <el-descriptions
      :column="1"
      size="small"
      border
      style="margin-bottom: 16px"
    >
      <el-descriptions-item label="后端服务">{{
        settings.backendAddress
      }}</el-descriptions-item>
      <el-descriptions-item label="页面地址">{{
        settings.browserAddress
      }}</el-descriptions-item>
      <el-descriptions-item label="采集代理">{{
        settings.proxyAddress
      }}</el-descriptions-item>
      <el-descriptions-item label="启动配置"
        ><span style="overflow-wrap: anywhere">{{
          settings.configFile
        }}</span></el-descriptions-item
      >
    </el-descriptions>
    <p class="muted">
      端口在上述配置文件修改，重启项目生效；页面不会另外保存一套端口。
    </p>
    <el-tabs v-model="settingsTab">
      <el-tab-pane label="协议与缓存" name="protocol">
        <p v-if="settings.catalogError" class="error-bar">
          {{ settings.catalogError }}
        </p>
        <el-form label-width="120px">
          <el-form-item label="目录 / 协议文件"
            ><el-input
              v-model="settings.competitorPath"
              placeholder="客户端根目录、任意子目录，或 thrift_data.js 完整路径"
              @input="sourceChanged"
          /></el-form-item>
          <el-form-item v-if="protocolCandidates.length" label="候选协议文件">
            <el-select
              v-model="selectedProtocolFile"
              placeholder="找到多份，请选择一份"
              style="width: 100%"
            >
              <el-option
                v-for="item in protocolCandidates"
                :key="item.path"
                :value="item.path"
                :label="`${item.relativePath} · ${Math.ceil(item.sizeBytes / 1024)} KB`"
                :title="item.path"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            ><el-button :loading="settingsBusy" @click="findProtocolFiles"
              >查找协议文件</el-button
            ><el-button
              type="primary"
              :loading="settingsBusy"
              @click="reloadCatalog"
              >加载协议并记住路径</el-button
            ><span class="muted"
              >当前 {{ catalogCount }} 条协议</span
            ></el-form-item
          >
          <el-form-item v-if="settings.loadedProtocolFile" label="当前加载文件">
            <span class="muted" style="overflow-wrap: anywhere">{{
              settings.loadedProtocolFile
            }}</span>
          </el-form-item>
          <el-form-item label="每连接消息上限"
            ><el-input-number
              v-model="settings.maxMessages"
              :min="CACHE_BUDGET.minMessages"
              :max="CACHE_BUDGET.maxMessages"
              :step="1000"
          /></el-form-item>
          <el-form-item label="每连接容量 MiB"
            ><el-input-number
              v-model="settings.maxCacheMiB"
              :min="CACHE_BUDGET.minMiB"
              :max="CACHE_BUDGET.maxMiB"
          /></el-form-item>
        </el-form>
        <p class="muted">
          超过任一上限淘汰最旧消息，序号不重用。临时记录退出后不保留；停止时或停止后可另行保存。
        </p>
      </el-tab-pane>
      <el-tab-pane label="采集目标" name="collector">
        <CollectorSettingsPanel
          v-if="settingsDialog && settingsTab === 'collector'"
        />
      </el-tab-pane>
    </el-tabs>
    <template #footer
      ><el-button @click="settingsDialog = false">关闭</el-button
      ><el-button
        v-if="settingsTab === 'protocol'"
        type="primary"
        :loading="settingsBusy"
        @click="saveSettings"
        >保存缓存设置</el-button
      ></template
    >
  </el-dialog>
</template>
