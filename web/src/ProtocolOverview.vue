<script setup lang="ts">
import { MAX_PROTOCOL_OVERVIEW_GROUPS } from "./constants";
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { api, type Packet } from "./types";
import { RequestGate } from "./workspaceRefresh";
interface Group extends Packet {
  count: number;
  firstIndex: number;
  lastIndex: number;
}
interface Overview {
  messageCount: number;
  protocolDistribution: Group[];
  distributionTruncated: boolean;
  protocolGroupCount: number;
}
const props = defineProps<{ captureId: string }>();
const emit = defineEmits<{
  only: [rows: Packet[]];
  exclude: [rows: Packet[]];
}>();
const data = shallowRef<Overview>();
const chosen = shallowRef<Group[]>([]);
const busy = ref(false);
const error = ref("");
const keyword = ref("");
const gate = new RequestGate();
const groups = computed(() =>
  (data.value?.protocolDistribution || []).filter((row) =>
    `${row.protocolName} ${row.protocolId}`
      .toLowerCase()
      .includes(keyword.value.toLowerCase()),
  ),
);
async function refresh() {
  const request = gate.begin();
  busy.value = true;
  error.value = "";
  try {
    const result = await api<Overview>(
      `/api/captures/${props.captureId}/protocols`,
      { signal: request.signal },
    );
    if (request.current()) {
      data.value = result;
      chosen.value = [];
    }
  } catch (cause) {
    if (request.current()) error.value = String(cause);
  } finally {
    if (request.current()) busy.value = false;
  }
}
watch(
  () => props.captureId,
  () => {
    data.value = undefined;
    chosen.value = [];
    void refresh();
  },
  { immediate: true },
);
onBeforeUnmount(() => gate.cancel());
</script>
<template>
  <section class="protocol-overview">
    <div class="overview-tools">
      <strong>协议概览</strong
      ><span class="muted"
        >整份记录当前保留范围，不应用消息列表筛选；手动刷新</span
      >
      <el-input
        v-model="keyword"
        placeholder="查找协议"
        clearable
        style="width: 180px"
      />
      <el-button :loading="busy" @click="refresh">刷新</el-button>
      <el-button :disabled="!chosen.length" @click="emit('only', chosen)"
        >仅看所选</el-button
      >
      <el-button :disabled="!chosen.length" @click="emit('exclude', chosen)"
        >排除所选</el-button
      >
    </div>
    <p v-if="error" class="error-bar">
      {{ error }} <el-button @click="refresh">重试</el-button>
    </p>
    <p v-if="data?.distributionTruncated" class="warning-bar">
      共 {{ data.protocolGroupCount }} 组，仅展示次数最多的
      {{ MAX_PROTOCOL_OVERVIEW_GROUPS }} 组。
    </p>
    <el-table
      :data="groups"
      height="100%"
      row-key="firstIndex"
      @selection-change="chosen = $event"
      empty-text="没有可展示的协议"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column
        prop="protocolName"
        label="协议"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column prop="protocolId" label="协议 ID" width="95" />
      <el-table-column prop="direction" label="方向" width="65" />
      <el-table-column prop="count" label="次数" sortable width="95" />
      <el-table-column label="占比" width="80"
        ><template #default="{ row }"
          >{{
            ((row.count / Math.max(1, data?.messageCount || 0)) * 100).toFixed(
              1,
            )
          }}%</template
        ></el-table-column
      >
      <el-table-column prop="lastIndex" label="最近消息 #" width="120" />
      <el-table-column label="操作" width="110"
        ><template #default="{ row }"
          ><el-button text @click="emit('only', [row])"
            >仅看</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
</template>
<style scoped>
.protocol-overview {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.overview-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px;
}
.protocol-overview .el-table {
  flex: 1;
  min-height: 0;
}
</style>
