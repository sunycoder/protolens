<script setup lang="ts">
import { ref } from "vue";
import type { Capture } from "./types";
import type { useWorkspaceFilters } from "./useWorkspaceFilters";
import type {
  defaultColumns,
  DetailLayout,
  WorkspaceQuery,
} from "./workspaceInteractions";
import { MessageDirection } from "./domain";
import { statusText } from "./capturePresentation";
import { connectionLabel } from "./connectionIdentity";
import SearchHistoryInput from "./SearchHistoryInput.vue";
import FilterChipEditor from "./FilterChipEditor.vue";
const props = defineProps<{
  filters: ReturnType<typeof useWorkspaceFilters>;
  capture?: Capture;
  dictionary: string;
  columns: typeof defaultColumns;
  supportsAnalysis: boolean;
  interactiveLoading: boolean;
  hasUndo: boolean;
  batchExcluding: boolean;
  actions: {
    filter: () => void;
    openResearch: (mode?: string) => void;
    undoQuickQuery: () => Promise<void>;
    toggleBatchMode: () => void;
    resetLayout: () => Promise<void>;
    removeFilter: (key: string, value?: string) => void;
    quickQuery: (patch: Partial<WorkspaceQuery>) => Promise<void>;
    resetFilters: () => void;
  };
}>();
const detailDock = defineModel<DetailLayout>("detailDock", { required: true });
const emit = defineEmits<{ views: [] }>();
const {
  search,
  protocolId,
  messageIndex,
  appliedProtocolId,
  appliedMessageIndex,
  include,
  exclude,
  appliedInclude,
  appliedExclude,
  direction,
  status,
  connection,
  hideHeartbeat,
  selectedRange,
  appliedSearch,
  order,
  expandedTags,
  advancedFilters,
  tableSettings,
  filterDialog,
  hasDraft,
  activeFilters,
  visibleFilters,
  currentQuery,
  displayedQuery,
} = props.filters;
const {
  filter,
  openResearch,
  undoQuickQuery,
  toggleBatchMode,
  resetLayout,
  removeFilter,
  quickQuery,
  resetFilters,
} = props.actions;
const searchHistory = ref<InstanceType<typeof SearchHistoryInput>>();
defineExpose({ record: () => searchHistory.value?.record() });
</script>
<template>
  <div class="workspace-tools">
    <div class="search-toolbar" role="search" aria-label="协议与消息搜索">
      <SearchHistoryInput
        ref="searchHistory"
        v-model="search"
        :dictionary="dictionary"
        @search="filter"
      />
      <el-input
        v-model="protocolId"
        clearable
        aria-label="协议 ID"
        placeholder="协议 ID"
        @keyup.enter="filter"
        @clear="filter"
      />
      <el-input
        v-model="messageIndex"
        clearable
        aria-label="消息序号"
        placeholder="消息序号 #"
        @keyup.enter="filter"
        @clear="filter"
      />
      <el-button type="primary" :loading="interactiveLoading" @click="filter"
        >查询</el-button
      >
    </div>
    <div class="view-toolbar">
      <el-dropdown trigger="click" :disabled="!capture || !supportsAnalysis"
        ><el-button :disabled="!capture || !supportsAnalysis"
          >分析工具</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item @click="openResearch('time')"
              >时间定位</el-dropdown-item
            ><el-dropdown-item @click="openResearch('review')"
              >操作回顾</el-dropdown-item
            ><el-dropdown-item @click="openResearch('compare')"
              >两次操作对照</el-dropdown-item
            ><el-dropdown-item @click="openResearch('anomalies')"
              >异常协议</el-dropdown-item
            ><el-dropdown-item @click="openResearch('notes')"
              >消息 / 字段批注</el-dropdown-item
            ><el-dropdown-item @click="openResearch('columns')"
              >字段列与关注</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
      <el-popover
        v-model:visible="advancedFilters"
        trigger="click"
        placement="bottom-start"
        :width="420"
      >
        <template #reference
          ><el-button
            :type="advancedFilters ? 'primary' : 'default'"
            :aria-expanded="advancedFilters"
            >更多筛选</el-button
          ></template
        >
        <div class="advanced-filters filter-popover">
          <el-select
            v-model="direction"
            placeholder="全部方向"
            aria-label="方向筛选"
            clearable
            @change="filter"
            ><el-option
              label="发送 CS"
              :value="MessageDirection.CS" /><el-option
              label="接收 SC"
              :value="MessageDirection.SC"
          /></el-select>
          <el-select
            v-model="status"
            placeholder="全部状态"
            aria-label="解码状态筛选"
            clearable
            @change="filter"
            ><el-option
              v-for="(label, key) in statusText"
              :key="key"
              :label="label"
              :value="key"
          /></el-select>
          <el-select
            v-model="connection"
            placeholder="全部连接"
            aria-label="连接筛选"
            clearable
            @change="filter"
            ><el-option
              v-for="item in capture?.connections || []"
              :key="item.id"
              :value="item.id"
              :label="connectionLabel(item)"
              :title="item.endpoint"
          /></el-select>
          <el-checkbox v-model="hideHeartbeat" @change="filter"
            >隐藏心跳</el-checkbox
          >
        </div>
        <div v-if="capture?.marks.length" class="marks filter-marks">
          <span class="muted">操作区间</span
          ><el-select
            v-model="selectedRange"
            clearable
            placeholder="全部消息"
            @change="filter"
            ><el-option
              v-for="(item, index) in capture.marks"
              :key="index"
              :label="`${item.expired ? '[部分已淘汰] ' : ''}${item.name} [${item.from}–${item.to}]`"
              :value="String(index + 1)" /></el-select
          ><span class="muted">区间不自动证明请求响应关系。</span>
        </div>
      </el-popover>
      <el-select v-model="order" @change="filter" aria-label="消息排序">
        <el-option label="最新在前 ↓" value="desc" /><el-option
          label="最早在前 ↑"
          value="asc"
        />
      </el-select>
      <el-button @click="filterDialog = true">协议过滤</el-button>
      <el-button @click="emit('views')">常用视图</el-button>
      <el-button
        v-if="hasUndo"
        :disabled="interactiveLoading"
        @click="undoQuickQuery"
        >撤销筛选操作</el-button
      >
      <span v-if="hasDraft" class="muted" role="status">输入有待提交条件</span>
      <el-button
        :type="batchExcluding ? 'primary' : 'default'"
        :disabled="!capture"
        @click="toggleBatchMode"
      >
        {{ batchExcluding ? "退出批量排除" : "批量排除" }}
      </el-button>
      <el-popover
        v-model:visible="tableSettings"
        trigger="click"
        placement="bottom-end"
        :width="360"
      >
        <template #reference><el-button>表格设置</el-button></template>
        <div class="column-settings">
          <strong>显示列</strong>
          <el-checkbox v-model="columns.time">时间</el-checkbox
          ><el-checkbox v-model="columns.protocolId">协议 ID</el-checkbox>
          <el-checkbox v-model="columns.status">解码状态</el-checkbox
          ><el-checkbox v-model="columns.bytes">字节数</el-checkbox>
          <el-checkbox v-model="columns.sequence">帧序号</el-checkbox
          ><el-checkbox v-model="columns.connection">连接</el-checkbox>
          <el-checkbox v-model="columns.actions">行操作</el-checkbox>
        </div>
        <p class="muted">详情布局，自动模式按数据区实际宽高选择</p>
        <el-radio-group v-model="detailDock" size="small"
          ><el-radio-button value="auto">自动</el-radio-button
          ><el-radio-button value="right">右侧</el-radio-button
          ><el-radio-button value="bottom">底部</el-radio-button
          ><el-radio-button value="overlay"
            >浮层</el-radio-button
          ></el-radio-group
        >
        <p class="muted">
          空间不足时暂用浮层，保持列表视野；方向键阅读，Esc 收起详情。
        </p>
        <el-button size="small" @click="resetLayout">恢复默认布局</el-button>
      </el-popover>
    </div>
    <div
      v-if="activeFilters.length"
      class="active-filters"
      aria-label="当前生效条件"
    >
      <FilterChipEditor
        v-for="item in visibleFilters"
        :key="item.key + ':' + (item.value || '')"
        :item="item"
        :query="displayedQuery || currentQuery()"
        :marks="capture?.marks"
        :connections="capture?.connections"
        @remove="removeFilter(item.key, item.value)"
        @apply="quickQuery"
      />

      <el-button
        v-if="activeFilters.length > 3"
        text
        size="small"
        :aria-expanded="expandedTags"
        @click="expandedTags = !expandedTags"
        >{{
          expandedTags ? "收起条件" : `另有 ${activeFilters.length - 3} 条条件`
        }}</el-button
      >
      <el-button
        v-if="activeFilters.length"
        text
        size="small"
        @click="resetFilters"
        >重置全部条件</el-button
      >
    </div>
  </div>
  <el-dialog v-model="filterDialog" title="协议过滤" width="540px">
    <p class="muted">
      协议名或协议 ID 普通词按模糊包含匹配；id:12 精确匹配协议 ID，name:GcUpdate
      精确匹配协议名。支持逗号分隔，同组任一匹配、排除优先。行内与批量快捷操作自动使用精确条件。
    </p>
    <el-form label-width="90px">
      <el-form-item label="仅包含"
        ><el-input v-model="include" placeholder="例如：Donate,2361" clearable
      /></el-form-item>
      <el-form-item label="排除"
        ><el-input
          v-model="exclude"
          placeholder="例如：MapAppear,SynchronizeTime"
          clearable
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button
        @click="
          include = '';
          exclude = '';
          filter();
          filterDialog = false;
        "
        >清除过滤</el-button
      ><el-button
        type="primary"
        @click="
          filter();
          filterDialog = false;
        "
        >应用</el-button
      ></template
    >
  </el-dialog>
</template>
