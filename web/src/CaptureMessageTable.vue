<script setup lang="ts">
import { ref } from "vue";
import type { TableInstance } from "element-plus";
import type { Packet } from "./types";
import type { FieldColumn } from "./analysisTools";
import type { useWorkspaceLayout } from "./useWorkspaceLayout";
import { MessageDirection } from "./domain";
import { protocolChoice, type ProtocolChoice } from "./protocolSelection";
import type { defaultColumns } from "./workspaceInteractions";
import MessageRowActions from "./MessageRowActions.vue";
import HighlightedText from "./HighlightedText.vue";
import CopyText from "./CopyText.vue";
import { messageTime as time } from "./capturePresentation";
const props = defineProps<{
  rows: Packet[];
  visibleRows: Packet[];
  capture?: { id: string };
  busy: boolean;
  tableRenderDeps: unknown[];
  highlightedRow?: number;
  recentRows: Set<number>;
  selectedOperationRange?: { from: number; to: number };
  batchExcluding: boolean;
  selectedProtocols: ProtocolChoice[];
  columns: typeof defaultColumns;
  fieldColumns: FieldColumn[];
  workspaceLayout: ReturnType<typeof useWorkspaceLayout>;
  detailQuery: string;
  displayedQuery?: { q: string };
  navigatingSearchResult: boolean;
  foldRepeats: boolean;
  groupedRepeats: Map<number, Packet[]>;
  expandedRepeats: Set<number>;
  messageGrouping: string;
  isWatched: (row: Packet) => boolean;
  statusText: Record<string, string>;
  actions: {
    tableRowClick: (
      row: Packet,
      column: unknown,
      event: MouseEvent,
    ) => Promise<void>;
    resetColumnFromHeader: (
      column: { property?: string; label?: string },
      event: MouseEvent,
    ) => void;
    toggleProtocolSelection: (row: Packet) => void;
    toggleWatch: (row: Packet) => void;
    openProtocol: (row: Packet, event?: MouseEvent) => void;
    openGroupPreview: (index: number) => void;
    excludeProtocol: (row: Packet) => void;
    pauseListForInteraction: (reason?: string) => void;
    jumpToContext: (
      row: Packet,
      scope?: "connection" | "all",
      remember?: boolean,
    ) => Promise<void>;
    onlyProtocol: (row: Packet) => void;
  };
}>();
const {
  tableRowClick,
  resetColumnFromHeader,
  toggleProtocolSelection,
  toggleWatch,
  openProtocol,
  openGroupPreview,
  excludeProtocol,
  pauseListForInteraction,
  jumpToContext,
  onlyProtocol,
} = props.actions;
const packetTable = ref<TableInstance>(),
  rowActions = ref<InstanceType<typeof MessageRowActions>>();
defineExpose({ table: packetTable, rowActions });
</script>
<template>
  <el-table
    v-memo="tableRenderDeps"
    ref="packetTable"
    class="packet-table"
    v-loading="busy"
    :data="visibleRows"
    :fit="true"
    :row-class-name="
      ({ row }: { row: Packet }) =>
        [
          row.index === highlightedRow ? 'located-row' : '',
          selectedOperationRange &&
          row.index >= selectedOperationRange.from &&
          row.index <= selectedOperationRange.to
            ? 'operation-selected-row'
            : '',
          recentRows.has(row.index) ? 'recent-row' : '',
          isWatched(row) ? 'watched-row' : '',
        ].join(' ')
    "
    height="100%"
    highlight-current-row
    empty-text="暂无消息，先导入包含 WebSocket 数据的 SAZ"
    @row-click="tableRowClick"
    border
    @header-dragend="workspaceLayout.resize"
    @header-dblclick="resetColumnFromHeader"
    row-key="index"
    size="small"
  >
    <template #empty><slot name="empty" /></template>
    <el-table-column v-if="batchExcluding" width="36" label="">
      <template #default="{ row }">
        <input
          type="checkbox"
          class="protocol-select"
          :aria-label="`选择协议 ${row.protocolName || (row.protocolId ?? '未知')}`"
          :disabled="!protocolChoice(row)"
          :checked="
            selectedProtocols.some(
              (item) => item.key === protocolChoice(row)?.key,
            )
          "
          @click.stop
          @change="toggleProtocolSelection(row)"
        />
      </template>
    </el-table-column>
    <el-table-column
      prop="index"
      label="消息序号"
      :width="workspaceLayout.width('index', 82)"
    />
    <el-table-column
      v-if="columns.time"
      label="时间"
      :width="workspaceLayout.width('时间', 108)"
      ><template #default="{ row }">{{
        time(row.time)
      }}</template></el-table-column
    >
    <el-table-column label="方向" :width="workspaceLayout.width('方向', 58)"
      ><template #default="{ row }"
        ><span class="direction" :class="row.direction.toLowerCase()">{{
          row.direction === MessageDirection.CS ? "↑ CS" : "↓ SC"
        }}</span></template
      ></el-table-column
    >
    <el-table-column
      prop="protocolName"
      label="协议"
      :width="workspaceLayout.width('protocolName', 230)"
      show-overflow-tooltip
      ><template #default="{ row }"
        ><span class="protocol-cell"
          ><button
            class="protocol-watch-toggle"
            :class="{ watched: isWatched(row) }"
            :aria-label="`${isWatched(row) ? '取消关注' : '关注'}协议 ${row.protocolName || row.protocolId}`"
            :aria-pressed="isWatched(row)"
            :disabled="!protocolChoice(row)"
            @click.stop="toggleWatch(row)"
          >
            {{ isWatched(row) ? "★" : "☆" }}</button
          ><button
            class="protocol-name protocol-button"
            :title="`${row.protocolName || '未识别消息'} · ${navigatingSearchResult ? '定位上下文并查看详情' : '查看详情'}`"
            @click.stop="openProtocol(row, $event)"
          >
            <HighlightedText
              :text="row.protocolName || '未识别消息'"
              :query="detailQuery"
            /></button
          ><button
            v-if="
              foldRepeats && (groupedRepeats.get(row.index)?.length || 0) > 1
            "
            class="repeat-toggle"
            :title="`${groupedRepeats.get(row.index)?.[0]?.time} 至 ${groupedRepeats.get(row.index)?.at(-1)?.time}；仅本页；${messageGrouping === 'protocol' ? '同协议组，内容可不同，可能穿插其他协议，代表行只展示一条样本' : '业务内容相同的连续消息'}；展开核对原消息`"
            @click.stop="openGroupPreview(row.index)"
          >
            {{ expandedRepeats.has(row.index) ? "收起" : "展开" }}
            {{ messageGrouping === "protocol" ? "组" : "×"
            }}{{ groupedRepeats.get(row.index)?.length }}
          </button></span
        ></template
      ></el-table-column
    >
    <el-table-column
      v-for="field in fieldColumns"
      :key="field.path"
      :label="field.label"
      :prop="field.path"
      :width="workspaceLayout.width('field.label', 150)"
      show-overflow-tooltip
      ><template #default="{ row }"
        ><span :title="field.path">{{
          row.fieldValues?.[field.path] ?? "—"
        }}</span></template
      ></el-table-column
    >
    <el-table-column
      prop="preview"
      label="消息内容摘要"
      :width="workspaceLayout.widths.value.preview"
      min-width="220"
      show-overflow-tooltip
      ><template #default="{ row }"
        ><HighlightedText
          :text="row.preview"
          :query="displayedQuery?.q" /></template
    ></el-table-column>
    <el-table-column
      v-if="columns.actions"
      label="操作"
      :width="workspaceLayout.width('操作', 86)"
    >
      <template #default="{ row }">
        <button
          class="row-exclude"
          :aria-label="`排除协议 ${row.protocolName || (row.protocolId ?? '未知')}`"
          :disabled="!protocolChoice(row)"
          title="排除此协议的全部消息"
          @click.stop="excludeProtocol(row)"
        >
          排除
        </button>
        <button
          class="row-menu"
          :aria-label="`消息 #${row.index} 操作`"
          aria-haspopup="dialog"
          @pointerdown.stop="pauseListForInteraction()"
          @click.stop="rowActions?.open($event, row)"
        >
          ⋯
        </button>
      </template>
    </el-table-column>
    <el-table-column
      v-if="columns.protocolId"
      prop="protocolId"
      label="协议 ID"
      :width="workspaceLayout.width('protocolId', 90)"
    />
    <el-table-column
      v-if="columns.status"
      label="状态"
      :width="workspaceLayout.width('状态', 92)"
      ><template #default="{ row }"
        ><span class="decode-state" :class="row.status.toLowerCase()">{{
          statusText[row.status] || row.status
        }}</span></template
      ></el-table-column
    >
    <el-table-column
      v-if="columns.sequence"
      prop="sequence"
      label="帧序号"
      :width="workspaceLayout.width('sequence', 76)"
    />
    <el-table-column
      v-if="columns.bytes"
      label="字节"
      :width="workspaceLayout.width('字节', 76)"
      ><template #default="{ row }">{{
        row.inputFormat === "DECOMPRESSED_MESSAGE" ? row.bytes : row.wireBytes
      }}</template></el-table-column
    >
    <el-table-column
      v-if="columns.connection"
      label="连接"
      :width="workspaceLayout.width('连接', 112)"
      show-overflow-tooltip
      ><template #default="{ row }"
        ><CopyText :value="row.connection" label="完整连接 ID" /></template
    ></el-table-column>
  </el-table>
  <MessageRowActions
    ref="rowActions"
    :rows="rows"
    :capture-id="capture?.id"
    @opening="pauseListForInteraction"
    @context="jumpToContext"
    @only="onlyProtocol"
    @exclude="excludeProtocol"
  />
</template>
