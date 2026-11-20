<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { MAX_UPLOAD_BYTES } from "./constants";
import { api, post, type Capture } from "./types";
import { recordGroups } from "./analysisTools";
import { captureName } from "./capturePresentation";
const props = defineProps<{
  captures: Capture[];
  capture?: Capture;
  unread: Record<string, number>;
  imported: (capture: Capture) => Promise<void>;
}>();
const emit = defineEmits<{
  collapse: [];
  reload: [];
  open: [capture: Capture];
  delete: [capture: Capture];
  "clear-error": [];
  error: [error: unknown];
}>();
const localPath = ref(""),
  fileInput = ref<HTMLInputElement>();
const groupedRecords = computed(() => recordGroups(props.captures));
const busy = defineModel<boolean>("busy", { required: true });
const failure = (error: unknown) => emit("error", error);
async function imported(item: Capture) {
  await props.imported(item);
  ElMessage.success(
    `已导入 ${item.messageCount} 条消息，已解码 ${item.counts.DECODED || 0} 条`,
  );
}
async function importPath() {
  if (!localPath.value.trim() || busy.value) return;
  busy.value = true;
  emit("clear-error");
  try {
    await imported(
      await post<Capture>("/api/import/path", { path: localPath.value.trim() }),
    );
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
  }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || busy.value) return;
  if (file.size > MAX_UPLOAD_BYTES) {
    failure(new Error("文件超过 128 MiB"));
    input.value = "";
    return;
  }
  busy.value = true;
  emit("clear-error");
  try {
    await imported(
      await api<Capture>(
        "/api/import/saz?name=" + encodeURIComponent(file.name),
        { method: "POST", body: file },
      ),
    );
  } catch (error) {
    failure(error);
  } finally {
    busy.value = false;
    input.value = "";
  }
}
</script>
<template>
  <aside class="sidebar">
    <div class="section-heading">
      抓包记录
      <el-button
        text
        size="small"
        @click="emit('collapse')"
        :disabled="!capture"
        aria-label="收起记录侧栏"
        >收起</el-button
      >
      <el-button text size="small" @click="emit('reload')">刷新</el-button>
    </div>
    <div class="capture-list">
      <template v-for="group in groupedRecords" :key="group.key">
        <details v-if="group.items.length > 1" open class="record-group">
          <summary :title="group.label">
            {{ group.label }} · {{ group.items.length }} 次连接 / 记录
          </summary>
          <div class="muted">按已确认角色归组，消息与连接仍独立。</div>
          <div
            v-for="item in group.items"
            :key="item.id"
            role="button"
            tabindex="0"
            class="capture-item"
            :class="{ active: capture?.id === item.id }"
            @click="emit('open', item)"
            @keydown.enter.self="emit('open', item)"
          >
            <el-button
              class="delete-capture"
              text
              size="small"
              type="danger"
              :disabled="item.liveState === 'CAPTURING'"
              @click.stop="emit('delete', item)"
              >删除</el-button
            ><strong>{{ captureName(item) }}</strong
            ><small
              >{{ item.messageCount.toLocaleString() }} 条 ·
              {{ item.temporary ? "临时" : "已保存" }} ·
              {{ item.liveState === "CAPTURING" ? "采集中" : "已结束" }}
              <span v-if="unread[item.id]" class="new-count"
                >+{{ unread[item.id] }}</span
              ></small
            >
          </div>
        </details>
        <div
          v-else
          role="button"
          tabindex="0"
          @keydown.enter.self="emit('open', item)"
          v-for="item in group.items"
          :key="item.id || item.name"
          class="capture-item"
          :class="{ active: capture?.id === item.id }"
          :title="item.name"
          @click="emit('open', item)"
        >
          <el-button
            class="delete-capture"
            text
            size="small"
            type="danger"
            :disabled="item.liveState === 'CAPTURING'"
            @click.stop="emit('delete', item)"
            title="删除记录"
            >删除</el-button
          >
          <strong>{{ captureName(item) }}</strong
          ><small v-if="item.id"
            >{{ item.messageCount.toLocaleString() }} 条消息 ·
            {{ item.temporary ? "临时" : "已保存" }} ·
            {{ item.liveState === "CAPTURING" ? "采集中" : "已结束" }}
            <span v-if="unread[item.id]" class="new-count"
              >+{{ unread[item.id] }}</span
            ></small
          ><small v-else>{{ item.error }}</small>
        </div>
      </template>
      <div v-if="!captures.length" class="muted empty-small">
        尚无抓包记录<br />导入 SAZ 后保存在本机
      </div>
    </div>
    <div class="sidebar-bottom">
      <b>本地导入</b>
      <p class="muted">一次导出整个 WebSocket 会话，批量解析双向消息。</p>
      <el-button type="primary" :loading="busy" @click="fileInput?.click()"
        >选择 SAZ 文件</el-button
      ><input
        ref="fileInput"
        type="file"
        accept=".saz"
        hidden
        @change="upload"
      /><el-input
        v-model="localPath"
        placeholder="或填写本机 SAZ 绝对路径"
        :disabled="busy"
        @keyup.enter="importPath"
      /><el-button
        :loading="busy"
        :disabled="!localPath.trim()"
        @click="importPath"
        >从路径导入</el-button
      >
    </div>
  </aside>
</template>
