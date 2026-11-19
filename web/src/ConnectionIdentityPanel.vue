<script setup lang="ts">
import CopyText from "./CopyText.vue";
import { MAX_CONNECTION_NOTE_LENGTH, MAX_IDENTITY_HISTORY } from "./constants";
import { IdentityState } from "./domain";
import { ref, watch } from "vue";
import { api, post, type Capture } from "./types";
import { connectionLabel } from "./connectionIdentity";
import { RequestGate } from "./workspaceRefresh";
import { onBeforeUnmount } from "vue";
const props = defineProps<{ modelValue: boolean; captureId?: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  saved: [capture: Capture];
}>();
const capture = ref<Capture>();
const notes = ref<Record<string, string>>({});
const busy = ref(false);
const saving = ref(false);
const error = ref("");
const gate = new RequestGate();
async function load() {
  if (!props.modelValue || !props.captureId) return;
  const request = gate.begin();
  busy.value = true;
  error.value = "";
  capture.value = undefined;
  try {
    const result = await api<Capture>(
      `/api/captures/${props.captureId}/overview`,
      { signal: request.signal },
    );
    if (request.current()) {
      capture.value = result;
      notes.value = Object.fromEntries(
        result.connections.map((item) => [item.id, item.note || ""]),
      );
    }
  } catch (cause) {
    if (request.current()) error.value = String(cause);
  } finally {
    if (request.current()) busy.value = false;
  }
}
async function save(connectionId: string) {
  const id = capture.value?.id;
  if (!id || saving.value) return;
  saving.value = true;
  error.value = "";
  try {
    const identity = capture.value?.connections.find(
      (item) => item.id === connectionId,
    )?.identity;
    const result = await post<Capture>(`/api/captures/${id}/connection-note`, {
      connectionId,
      note: notes.value[connectionId] || "",
      ...(identity
        ? { expectedIdentity: identity }
        : { expectedIdentity: null }),
    });
    emit("saved", result);
    if (props.captureId === id && props.modelValue) capture.value = result;
  } catch (cause) {
    error.value = String(cause);
  } finally {
    saving.value = false;
  }
}
async function reidentify() {
  const id = capture.value?.id;
  if (!id || saving.value) return;
  saving.value = true;
  error.value = "";
  try {
    const result = await post<Capture>(`/api/captures/${id}/reidentify`, {});
    emit("saved", result);
    if (props.captureId === id && props.modelValue) {
      capture.value = result;
      notes.value = Object.fromEntries(
        result.connections.map((item) => [item.id, item.note || ""]),
      );
    }
  } catch (cause) {
    error.value = String(cause);
  } finally {
    saving.value = false;
  }
}
watch(
  () => [props.modelValue, props.captureId],
  () => {
    gate.cancel();
    if (props.modelValue) void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => gate.cancel());
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="连接账号信息与备注"
    width="min(760px,94vw)"
    align-center
    class="identity-dialog"
  >
    <p class="muted">
      自动身份来自该连接已确认的当前玩家协议。备注为人工标记，不代表账号认证；重新登录或角色切换后清除旧备注。新连接不按
      URL 自动继承。
    </p>
    <p v-if="busy" role="status">读取连接信息…</p>
    <el-button
      :loading="saving"
      :disabled="!capture || busy"
      @click="reidentify"
      >从已有消息重新识别</el-button
    >
    <p v-if="error" class="error-bar">
      {{ error }} <el-button @click="load">重新读取</el-button>
    </p>
    <section
      v-for="connection in capture?.connections || []"
      :key="connection.id"
      class="identity-section"
    >
      <strong>{{ connectionLabel(connection) }}</strong>
      <p class="muted identity-endpoint">连接 ID：{{ connection.id }}</p>
      <p class="muted identity-endpoint">{{ connection.endpoint }}</p>
      <p v-if="connection.identity?.state === IdentityState.IDENTIFIED">
        账号 ID：<CopyText
          v-if="connection.identity.accountId"
          :value="connection.identity.accountId"
          label="账号 ID"
        /><span v-else>未提供</span> · 角色 ID：<CopyText
          :value="connection.identity.roleId || '未提供'"
          label="角色 ID"
        />
        · 区服：{{ connection.identity.serverId || "未提供" }}
      </p>
      <p v-else class="muted">
        {{
          connection.identity?.state === IdentityState.PENDING
            ? "已捕获登录请求，尚未确认玩家身份。"
            : "当前连接尚无可确认的玩家身份。"
        }}
        先点击“从已有消息重新识别”；如果登录消息已被淘汰或未捕获，可手动备注。重新登录通常会创建新连接，不会补齐本条旧连接。
      </p>
      <p v-if="connection.identity" class="muted">
        {{
          connection.identity.state === IdentityState.IDENTIFIED
            ? "识别依据"
            : "已捕获"
        }}：{{ connection.identity.sourceProtocol }} · 消息 #{{
          connection.identity.sourceIndex
        }}
        {{
          connection.identity.state === IdentityState.IDENTIFIED
            ? " 起；此前消息不自动归入该身份。"
            : "；这只是登录请求，不代表已确认账号。"
        }}
      </p>
      <div class="identity-note">
        <el-input
          v-model="notes[connection.id]"
          :maxlength="MAX_CONNECTION_NOTE_LENGTH"
          show-word-limit
          placeholder="例如 主号、测试小号（最多40字）"
          :aria-label="`连接 ${connection.id} 的备注`"
        /><el-button :loading="saving" @click="save(connection.id)"
          >保存备注</el-button
        >
      </div>
      <details v-if="connection.identityHistory?.length">
        <summary>
          身份变更记录（{{ connection.identityHistory.length }}）
        </summary>
        <p v-if="connection.identityHistoryTruncated" class="warning-bar">
          仅保留最近
          {{ MAX_IDENTITY_HISTORY }} 次变更，较早身份无法从此历史恢复。
        </p>
        <p
          v-for="event in connection.identityHistory"
          :key="event.sourceIndex"
          class="muted"
        >
          #{{ event.sourceIndex }} ·
          {{
            event.state === IdentityState.PENDING
              ? "登录请求，身份待确认"
              : `${event.nickname || "未提供昵称"} · ${event.serverId || "?"}服 · 角色 ${event.roleId}`
          }}
          · {{ event.sourceProtocol }}
        </p>
      </details>
    </section>
    <template #footer
      ><el-button @click="emit('update:modelValue', false)"
        >关闭</el-button
      ></template
    >
  </el-dialog>
</template>
<style scoped>
.identity-section {
  border-top: 1px solid var(--border);
  padding: 12px 0;
}
.identity-note {
  display: flex;
  gap: 8px;
}
.identity-endpoint {
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  margin-top: 10px;
}
</style>
<style>
.identity-dialog {
  max-height: 90vh;
  display: flex;
  flex-direction: column;
}
.identity-dialog .el-dialog__body {
  min-height: 0;
  overflow-y: auto;
}
.identity-dialog .el-dialog__footer,
.identity-dialog .el-dialog__header {
  flex-shrink: 0;
}
</style>
