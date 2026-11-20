<script setup lang="ts">
import type { Packet } from "./types";
import type { FieldColumn } from "./analysisTools";
import type { useMessageSelection } from "./useMessageSelection";
import type { useMessageInspector } from "./useMessageInspector";
import { IdentityState } from "./domain";
import JsonTree from "./JsonTree.vue";
import TextPreview from "./TextPreview.vue";
import DetailInspectorTools from "./DetailInspectorTools.vue";
const props = defineProps<{
  selection: ReturnType<typeof useMessageSelection>;
  inspector: ReturnType<typeof useMessageInspector>;
  capture?: { id: string; operationContext?: string };
  maximized: boolean;
  supportsAnalysis: boolean;
  hasPrevious: boolean;
  hasNext: boolean;
  interactiveLoading: boolean;
  comparingPrevious: boolean;
  queryMetrics: { queryMs: number; lockWaitMs: number; snapshotMs: number };
  hasSearchOrigin: boolean;
  hasActiveFilters: boolean;
  detailReturn?: { packet: Packet };
  selectedOutsideRetention: boolean;
  selectedMissingFromList: boolean;
  laterMessages?: { count: number; truncated: boolean; latest?: Packet };
  laterError: string;
  fieldAnnotations: Record<string, string>;
  actions: {
    openProtocolQuery: (fromMessage?: boolean) => void;
    neighbour: (delta: number) => Promise<void>;
    comparePrevious: () => Promise<void>;
    editNote: (path?: string) => void;
    setComparisonBase: () => void;
    restoreDetailReturn: () => Promise<void>;
    locatePinnedMessage: () => Promise<void>;
    viewLatestProtocol: (compare?: boolean) => Promise<void>;
    select: (packet: Packet | undefined) => Promise<void>;
    addFieldColumn: (field: FieldColumn) => Promise<void>;
    watchField: (field: FieldColumn) => void;
    toggleDetailMaximize: () => Promise<void>;
  };
}>();
const emit = defineEmits<{ compare: [] }>();
const comparisonBase = defineModel<Packet>("comparisonBase");
const {
  selected,
  selectedRowIndex,
  detailTarget,
  detailLoading,
  detailError,
  detailOpen,
  detailMs,
} = props.selection;
const {
  tab,
  detailLocalQuery,
  detailQuery,
  fieldMatches,
  matchPaths,
  focusedField,
  expandedFields,
  rememberExpansion,
  focusField,
  detailMetadata,
  detailCopy,
  copy,
} = props.inspector;
const {
  openProtocolQuery,
  neighbour,
  comparePrevious,
  editNote,
  setComparisonBase,
  restoreDetailReturn,
  locatePinnedMessage,
  viewLatestProtocol,
  select,
  addFieldColumn,
  watchField,
  toggleDetailMaximize,
} = props.actions;
</script>
<template>
  <section class="detail-panel" :class="{ 'is-maximized': maximized }">
    <DetailInspectorTools
      v-if="selected"
      :body="selected.body"
      :message-key="`${capture?.id}:${selected.index}`"
      :focused-path="focusedField"
      :maximized="maximized"
      @focus="focusField"
      @search="detailLocalQuery = $event"
      @maximize="toggleDetailMaximize"
    />
    <div class="detail-heading">
      <div class="detail-title-row">
        <el-button
          text
          size="small"
          @click="detailOpen = false"
          aria-label="收起消息详情"
          >收起详情</el-button
        >
        <b :title="selected?.protocolName || detailTarget?.protocolName">{{
          selected?.protocolName || detailTarget?.protocolName || "消息详情"
        }}</b>
      </div>
      <span v-if="selected" class="muted detail-meta"
        >消息 #{{ selected.index }} · 协议 ID
        {{ selected.protocolId ?? "未知" }} · {{ selected.direction }} ·
        {{ selected.bytes }} 字节（解压后）</span
      ><el-button
        v-if="selected?.protocolId !== undefined"
        text
        size="small"
        @click="openProtocolQuery(true)"
        >查看协议定义</el-button
      >
      <el-button v-if="selected" text size="small" @click="copy">{{
        detailCopy.feedback.value || "复制 JSON"
      }}</el-button>
      <el-button
        v-if="selected"
        text
        size="small"
        @click="neighbour(-1)"
        :disabled="!hasPrevious || interactiveLoading"
        >上一条</el-button
      >
      <el-button
        v-if="selected"
        text
        size="small"
        @click="neighbour(1)"
        :disabled="!hasNext || interactiveLoading"
        >下一条</el-button
      >
      <el-button
        v-if="selected"
        text
        size="small"
        :loading="comparingPrevious"
        @click="comparePrevious"
        >对比上一条同协议</el-button
      >
      <el-button v-if="selected" text size="small" @click="editNote('')"
        >消息批注</el-button
      >
      <span
        v-if="selected"
        class="muted"
        :title="`查询 ${queryMetrics.queryMs.toFixed(1)}ms · 等锁 ${queryMetrics.lockWaitMs.toFixed(1)}ms · 快照 ${queryMetrics.snapshotMs.toFixed(1)}ms`"
        >详情 {{ detailMs.toFixed(0) }}ms</span
      >
      <el-popover
        v-if="selected"
        trigger="click"
        placement="bottom-end"
        :width="340"
      >
        <template #reference
          ><el-button text size="small"
            >消息对比{{
              comparisonBase ? ` · #${comparisonBase.index}` : ""
            }}</el-button
          ></template
        >
        <div class="detail-actions detail-popover">
          <span class="muted"
            >{{
              hasSearchOrigin
                ? "完整上下文"
                : hasActiveFilters
                  ? "当前筛选结果"
                  : "全部消息"
            }}
            · 上一条 / 下一条支持跨页</span
          >
          <el-button size="small" @click="setComparisonBase"
            >设为对比基准</el-button
          >
          <el-button
            v-if="comparisonBase"
            size="small"
            :disabled="
              comparisonBase.index === selected.index ||
              selectedRowIndex !== selected.index
            "
            @click="emit('compare')"
            >与 #{{ comparisonBase.index }} 对比变化</el-button
          >
          <el-button
            v-if="comparisonBase"
            text
            size="small"
            @click="comparisonBase = undefined"
            >清除基准</el-button
          >
        </div>
      </el-popover>
      <span v-if="!selected && detailTarget" class="muted detail-meta"
        >目标：消息 #{{ detailTarget.index }} · 协议 ID
        {{ detailTarget.protocolId ?? "未知" }}</span
      >
    </div>
    <div v-if="detailReturn" class="detail-reading-banner">
      <el-button text size="small" @click="restoreDetailReturn"
        >返回固定消息 #{{ detailReturn.packet.index }}</el-button
      >
    </div>
    <div v-if="selected" class="detail-reading-banner" role="status">
      <strong
        >固定查看：消息 #{{ selected.index }} ·
        {{ selected.protocolName || selected.protocolId }}</strong
      >
      <span v-if="selectedOutsideRetention">已不在保留范围，详情继续保留</span
      ><span v-else-if="selectedMissingFromList">当前列表未显示此消息</span>
      <el-button
        text
        size="small"
        :disabled="selectedOutsideRetention"
        @click="locatePinnedMessage"
        >定位此消息</el-button
      >
      <template v-if="laterMessages?.count"
        ><span
          >同连接、同方向、同协议后续{{
            laterMessages.truncated ? "至少" : ""
          }}
          {{ laterMessages.count }} 条（保留范围）</span
        ><el-button text size="small" @click="viewLatestProtocol(false)"
          >查看最新一条</el-button
        ><el-button text size="small" @click="viewLatestProtocol(true)"
          >与当前对比</el-button
        ></template
      >
      <span v-if="laterError" class="muted">{{ laterError }}</span>
    </div>
    <div v-if="detailLoading" class="detail-state" role="status">
      正在加载消息 #{{ detailTarget?.index }}，旧消息内容已隐藏。
    </div>
    <div v-else-if="detailError" class="detail-state" role="alert">
      {{ detailError
      }}<el-button size="small" @click="detailTarget && select(detailTarget)"
        >重试详情</el-button
      >
    </div>
    <template v-if="selected">
      <p v-if="!selected.connectionIdentity" class="muted operation-context">
        此消息身份未识别；连接后来识别到的账号不自动归入本条消息。
      </p>
      <p v-if="selected.connectionIdentity" class="muted operation-context">
        此消息身份：{{
          selected.connectionIdentity.state === IdentityState.IDENTIFIED
            ? `${selected.connectionIdentity.nickname || ""} · 角色 ${selected.connectionIdentity.roleId} · ${selected.connectionIdentity.serverId || "?"}服`
            : "登录请求，身份待确认"
        }}（依据 #{{ selected.connectionIdentity.sourceIndex }}
        {{ selected.connectionIdentity.sourceProtocol }}）
      </p>
      <p v-if="capture?.operationContext" class="muted operation-context">
        操作背景：{{ capture.operationContext }}
      </p>
      <details
        v-if="detailQuery && selected"
        class="field-matches"
        :open="fieldMatches.matches.length > 0"
      >
        <summary>
          搜索“{{ detailQuery }}”：消息体命中
          {{ fieldMatches.matches.length }} 个字段{{
            fieldMatches.truncated ? "（已达扫描或展示上限）" : ""
          }}
        </summary>
        <div v-if="fieldMatches.matches.length" class="field-match-list">
          <button
            v-for="match in fieldMatches.matches"
            :key="match.path"
            :class="{ active: focusedField === match.path }"
            @click="focusField(match.path)"
          >
            {{ match.path || "/" }} = {{ match.value }}
          </button>
        </div>
        <p v-else class="muted">
          已检查的消息体字段中未找到匹配；搜索也可能命中协议名或协议 ID。
        </p>
      </details>
      <div v-if="selected?.error" class="error-bar">
        {{ selected.error }}
      </div>
      <details
        v-if="selected?.warnings?.length"
        class="warning-bar compact-warning"
      >
        <summary>解码提示（{{ selected.warnings.length }}）</summary>
        <div>{{ selected.warnings.join("；") }}</div>
      </details>
      <p
        v-if="fieldAnnotations['']"
        class="message-note"
        :title="fieldAnnotations['']"
      >
        消息批注：{{ fieldAnnotations[""] }}
      </p>
      <el-tabs v-model="tab" class="detail-tabs">
        <el-tab-pane label="JSON 树" name="tree"
          ><div v-if="tab === 'tree'" class="detail-content">
            <JsonTree
              v-if="selected?.body !== undefined"
              :value="selected.body"
              :match-paths="matchPaths"
              :key="`${capture?.id}:${selected.index}`"
              :expansion-state="expandedFields"
              @expansion="rememberExpansion"
              :field-actions="supportsAnalysis"
              :annotations="fieldAnnotations"
              @column="addFieldColumn"
              @note="editNote"
              @watch-field="watchField"
              @focus="focusField"
              :focused-path="focusedField"
            />
            <div v-else class="empty-detail">
              {{
                selected
                  ? "当前消息未能完整解码，请查看原始包与失败原因。"
                  : "选中一条消息，查看协议字段与实际值"
              }}
            </div>
          </div></el-tab-pane
        >
        <el-tab-pane label="JSON 文本" name="json">
          <div v-if="tab === 'json'" class="detail-content">
            <TextPreview :value="selected.body" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="原始包体" name="hex"
          ><div v-if="tab === 'hex'" class="detail-content">
            <p class="muted">
              解压后的完整业务包（消息头 + Thrift Compact 消息体；头长见元数据
              headerBytes）
            </p>
            <TextPreview :value="selected.packetHex" format="hex" />
            <p class="muted">
              {{
                selected?.inputFormat === "DECOMPRESSED_MESSAGE"
                  ? "实时采集器已解压并重组消息，没有提供原始 wire 帧；上方保留的是完整业务包。"
                  : "SAZ 中的原始 WebSocket 帧（包含帧头、掩码与压缩字节）"
              }}
            </p>
            <TextPreview :value="selected.wireHex" format="hex" /></div
        ></el-tab-pane>
        <el-tab-pane label="字段定义" name="schema"
          ><div v-if="tab === 'schema'" class="detail-content">
            <p class="muted">
              来源：{{ selected?.catalog?.sourceFile
              }}<span v-if="selected?.definition?.line"
                >:{{ selected.definition.line }}</span
              >
              · 字典 SHA256
              {{ selected?.catalog?.sha256.slice(0, 16) }}
            </p>
            <p class="muted">
              字段定义格式：[字段名, Thrift 类型]；2 BOOL · 8 I32 · 10 I64 · 11
              STRING · [12,结构名] STRUCT · [15,元素类型] LIST ·
              [13,键类型,值类型] MAP
            </p>
            <JsonTree
              v-if="selected?.schema"
              :value="selected.schema"
              label="字段号"
            /></div
        ></el-tab-pane>
        <el-tab-pane label="元数据" name="metadata">
          <pre v-if="tab === 'metadata'" class="detail-content">{{
            JSON.stringify(detailMetadata, null, 2)
          }}</pre>
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>
