<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, post } from "./types";
interface ProxyStatus {
  supported: boolean;
  managed: boolean;
  autoPrompt: boolean;
  confirmationRequired: boolean;
  confirmationToken: string;
  existingIsTarget: boolean;
  canRestore: boolean;
  current: string;
  target: string;
  message: string;
}
const state = ref<ProxyStatus>();
defineProps<{ compact?: boolean }>();
const emit = defineEmits<{ changed: [status: ProxyStatus] }>();
watch(state, (value) => {
  if (value) emit("changed", value);
});
async function refresh() {
  state.value = await api<ProxyStatus>("/api/system-proxy");
}
defineExpose({ refresh });
const busy = ref(false);
const error = ref("");
let prompted = false;
async function connect(initial?: ProxyStatus) {
  busy.value = true;
  error.value = "";
  try {
    const current = initial || (await api<ProxyStatus>("/api/system-proxy"));
    state.value = current;
    let confirmed = false;
    if (current.confirmationRequired) {
      const message = current.existingIsTarget
        ? `当前系统代理已手动指向采集端口 ${current.target}。继续接管后，退出时将关闭这项手动代理；无法恢复未知的更早 VPN 配置。是否接管？`
        : `当前配置：${current.current}。可能由 VPN 或代理软件管理。继续将临时切换到 ${current.target}，原软件的系统代理功能可能暂时失效；退出时恢复本次接管前的配置。VPN 的 TUN 模式需自行处理。是否接管？`;
      await ElMessageBox.confirm(message, "检测到已有系统代理", {
        type: "warning",
        confirmButtonText: "临时接管",
        cancelButtonText: "保留原代理",
        closeOnClickModal: false,
      });
      confirmed = true;
    }
    state.value = await post<ProxyStatus>("/api/system-proxy/connect", {
      confirmed,
      confirmationToken: current.confirmationToken,
    });
    if (state.value.managed)
      ElMessage.success("已接管系统代理，退出工作台时自动恢复");
    else
      ElMessage.warning(state.value.message || "代理设置发生变化，请重新确认");
  } catch (failure) {
    if (failure === "cancel" || failure === "close") {
      state.value = await post<ProxyStatus>("/api/system-proxy/decline", {});
    } else {
      error.value = String(failure);
      ElMessage.error(error.value);
    }
  } finally {
    busy.value = false;
  }
}
async function restore() {
  busy.value = true;
  error.value = "";
  try {
    state.value = await post<ProxyStatus>("/api/system-proxy/restore", {});
    ElMessage.success(state.value.message);
  } catch (failure) {
    error.value = String(failure);
    ElMessage.error(error.value);
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  try {
    state.value = await api<ProxyStatus>("/api/system-proxy");
    if (state.value.autoPrompt && !prompted) {
      prompted = true;
      await connect(state.value);
    }
  } catch (failure) {
    error.value = String(failure);
  }
});
</script>

<template>
  <span class="system-proxy-controls">
    <template v-if="state?.supported">
      <el-tag
        v-if="!compact"
        size="small"
        :type="state.managed ? 'success' : 'info'"
        :title="state.current"
      >
        {{ state.managed ? "系统代理已接管" : "系统代理未接管" }}
      </el-tag>
      <el-button
        v-if="!state.managed"
        size="small"
        :loading="busy"
        @click="connect()"
        >接管系统代理</el-button
      >
      <el-button
        v-if="state.canRestore"
        size="small"
        :loading="busy"
        @click="restore"
        >恢复原代理</el-button
      >
    </template>
    <span v-if="error" class="muted" :title="error">系统代理操作未完成</span>
  </span>
</template>

<style scoped>
.system-proxy-controls {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
