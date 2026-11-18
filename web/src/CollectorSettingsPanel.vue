<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { api, post } from "./types";
interface CollectorSettings {
  captureHosts: string[];
  capturePathPrefixes: string[];
  activeCaptureHosts: string[];
  activeCapturePathPrefixes: string[];
  restartRequired: boolean;
}
const hosts = ref("");
const paths = ref("");
const current = ref<CollectorSettings>();
const loading = ref(true);
const saving = ref(false);
const error = ref("");
function apply(settings: CollectorSettings) {
  current.value = settings;
  hosts.value = settings.captureHosts.join("\n");
  paths.value = settings.capturePathPrefixes.join("\n");
}
function items(text: string) {
  return text
    .split(/[\n,，;；]+/)
    .map((value) => value.trim())
    .filter(Boolean);
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    apply(await api<CollectorSettings>("/api/collector/settings"));
  } catch (failure) {
    error.value = String(failure);
  } finally {
    loading.value = false;
  }
}
async function save() {
  saving.value = true;
  error.value = "";
  try {
    apply(
      await post<CollectorSettings>("/api/collector/settings", {
        captureHosts: items(hosts.value),
        capturePathPrefixes: items(paths.value),
      }),
    );
    ElMessage.success(
      current.value?.restartRequired
        ? "已保存到启动配置，重启工作台并重进游戏后生效"
        : "采集配置已保存",
    );
  } catch (failure) {
    error.value = String(failure);
  } finally {
    saving.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div v-loading="loading">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="current?.restartRequired"
      title="配置已保存，尚未生效。请先恢复系统代理，从托盘退出并重启工作台，再开启代理、重新进入游戏。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-form label-position="top" :disabled="loading || saving || !current">
      <el-form-item label="采集域名 / IPv4">
        <el-input
          v-model="hosts"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="每行一个，例如 game.example.com；不要填写 https://、端口或路径"
        />
        <span class="muted">域名自动包含其子域名；多个值也可用逗号分隔。</span>
      </el-form-item>
      <el-form-item label="WebSocket 路径前缀">
        <el-input
          v-model="paths"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="每行一个，例如 /websocket；填写 / 可匹配这些域名下的所有 WebSocket"
        />
        <span class="muted"
          >域名与路径同时匹配才采集；不含查询参数，区分大小写。</span
        >
      </el-form-item>
      <p v-if="current" class="muted">
        当前生效：{{ current.activeCaptureHosts.join("、") }} ·
        {{ current.activeCapturePathPrefixes.join("、") }}
      </p>
      <el-button type="primary" :loading="saving" @click="save"
        >保存采集目标</el-button
      >
      <el-button v-if="error" :disabled="loading || saving" @click="load"
        >重新读取</el-button
      >
    </el-form>
    <p class="muted">
      规则直接保存在上述启动配置文件，重启工作台生效；保存不会中断当前连接。
    </p>
  </div>
</template>
