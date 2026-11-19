<script setup lang="ts">
import { useInlineCopy } from "./useInlineCopy";
import { ref, shallowRef, computed, watch, onBeforeUnmount } from "vue";
import { ElMessage } from "element-plus";
import { api, type Capture } from "./types";
import { RequestGate } from "./workspaceRefresh";
import ProtocolFieldTree from "./ProtocolFieldTree.vue";
import {
  definitionUrl,
  fieldPathLabel,
  PROTOCOL_QUERY_PAGE_SIZE,
  type CatalogIdentity,
  type ProtocolSearchPage,
  type ProtocolDefinition,
  type ProtocolQueryContext,
  type ProtocolQueryTarget,
  type ProtocolQueryEntry,
} from "./protocolQuery";

const props = defineProps<{
  active: boolean;
  capture?: Capture;
  target?: ProtocolQueryTarget;
  catalogVersion?: string;
  returnLabel?: string;
}>();
const emit = defineEmits<{
  messages: [value: { captureId: string; protocolId: number }];
  close: [];
  settings: [];
}>();
const scope = ref("all");
const direction = ref("all");
const query = ref("");
const dictionary = ref("current");
const page = ref(1);
const results = shallowRef<ProtocolSearchPage>();
const selected = shallowRef<ProtocolDefinition>();
const busy = ref(false);
const detailBusy = ref(false);
const error = ref("");
const detailError = ref("");
const matchPath = ref("");
const stack = ref<{ key: string; path: string; label: string }[]>([]);
const searchGate = new RequestGate();
const detailGate = new RequestGate();
const sourceGate = new RequestGate();
const source = shallowRef<{
  path: string;
  fromLine: number;
  targetLine: number;
  lines: { line: number; text: string; lineTruncated: boolean }[];
  verified: boolean;
  note: string;
}>();
const sourceError = ref("");
const sourceBusy = ref(false);
const showingSource = ref(false);
const dictionaryChanged = ref(false);
const fieldPage = ref(1);
const captureId = computed(() =>
  dictionary.value === "record" ? props.capture?.id || "" : "",
);
const context = computed<ProtocolQueryContext>(() => ({
  captureId: results.value?.captureId || "",
  version: results.value?.catalog.version || "",
  contextCaptureId: props.capture?.id,
}));
const catalog = computed<CatalogIdentity | undefined>(
  () => results.value?.catalog,
);
const canShowMessages = computed(
  () =>
    !!selected.value?.occurrences &&
    selected.value.protocolId !== undefined &&
    selected.value.linkedCaptureId === props.capture?.id,
);
let initialized = false;
onBeforeUnmount(() => {
  searchGate.cancel();
  detailGate.cancel();
  sourceGate.cancel();
});

async function search(
  reset = true,
  target?: { key?: string; fieldPath?: string },
) {
  const request = searchGate.begin();
  detailGate.cancel();
  sourceGate.cancel();
  detailBusy.value = false;
  sourceBusy.value = false;
  dictionaryChanged.value = false;
  busy.value = true;
  error.value = "";
  selected.value = undefined;
  detailError.value = "";
  source.value = undefined;
  sourceError.value = "";
  if (reset) page.value = 1;
  const params = new URLSearchParams({
    captureId: captureId.value,
    q: query.value.trim(),
    scope: scope.value,
    direction: direction.value === "all" ? "" : direction.value,
    offset: String((page.value - 1) * PROTOCOL_QUERY_PAGE_SIZE),
    limit: String(PROTOCOL_QUERY_PAGE_SIZE),
  });
  try {
    const response = await api<ProtocolSearchPage>(
      `/api/protocol-query?${params}`,
      { signal: request.signal },
    );
    if (!request.current()) return;
    results.value = response;
    stack.value = [];
    if (target?.key) await open(target.key, target.fieldPath || "", false);
  } catch (cause) {
    if (request.current()) {
      error.value = String(cause);
      results.value = undefined;
    }
  } finally {
    if (request.current()) busy.value = false;
  }
}
async function open(key: string, path = "", push = true, offset = 0) {
  if (!results.value) return;
  const request = detailGate.begin();
  sourceGate.cancel();
  source.value = undefined;
  sourceError.value = "";
  showingSource.value = false;
  detailBusy.value = true;
  detailError.value = "";
  const before = selected.value;
  try {
    const response = await api<ProtocolDefinition>(
      definitionUrl(context.value, key, offset, path),
      { signal: request.signal },
    );
    if (!request.current()) return;
    if (push && before && before.key !== key)
      stack.value.push({
        key: before.key,
        path: matchPath.value,
        label: `${before.kind === "message" ? "协议" : "结构体"} ${before.name}`,
      });
    selected.value = response;
    matchPath.value = path;
    fieldPage.value =
      Math.floor(response.fieldOffset / response.fieldPageSize) + 1;
  } catch (cause) {
    if (request.current()) {
      selected.value = undefined;
      detailError.value = String(cause);
    }
  } finally {
    if (request.current()) detailBusy.value = false;
  }
}
async function back() {
  const previous = stack.value.pop();
  if (previous) await open(previous.key, previous.path, false);
}
function selectEntry(entry: ProtocolQueryEntry) {
  if (busy.value) return;
  stack.value = [];
  void open(entry.key, entry.matchPath || "", false);
}
function changeFieldPage(value: number) {
  if (selected.value)
    void open(
      selected.value.key,
      "",
      false,
      (value - 1) * selected.value.fieldPageSize,
    );
}
const inlineCopy = useInlineCopy();
async function copy(text: string) {
  await inlineCopy.copy(text);
}
async function showSource() {
  if (!selected.value) return;
  showingSource.value = true;
  const request = sourceGate.begin();
  sourceBusy.value = true;
  sourceError.value = "";
  try {
    const params = new URLSearchParams({
      captureId: context.value.captureId,
      version: context.value.version,
      key: selected.value.key,
    });
    const value = await api<NonNullable<typeof source.value>>(
      `/api/protocol-query/source?${params}`,
      { signal: request.signal },
    );
    if (request.current()) source.value = value;
  } catch (cause) {
    if (request.current()) sourceError.value = String(cause);
  } finally {
    if (request.current()) sourceBusy.value = false;
  }
}
function changeDictionary() {
  void search();
}
watch(
  () => props.target?.sequence,
  () => {
    const target = props.target;
    if (!target) return;
    dictionary.value =
      target.captureId && target.captureId === props.capture?.id
        ? "record"
        : "current";
    scope.value = "all";
    direction.value = "all";
    query.value = target.key || "";
    initialized = true;
    void search(true, target);
  },
  { immediate: true },
);
watch(
  () => props.active,
  (active) => {
    if (active && !initialized) {
      initialized = true;
      void search();
    }
  },
);
watch(
  () => props.capture?.id,
  () => {
    if (dictionary.value === "record") {
      if (!props.capture) dictionary.value = "current";
      void search();
    }
  },
);
watch(
  () => props.catalogVersion,
  (next, previous) => {
    if (initialized && dictionary.value === "current" && next !== previous)
      dictionaryChanged.value = true;
  },
);
</script>
<template>
  <section class="protocol-query-panel" aria-label="协议查询">
    <div class="query-heading">
      <strong>协议查询</strong
      ><span class="muted">查字典定义、字段与结构引用；无需抓包</span
      ><el-button @click="emit('close')">{{
        returnLabel || "返回抓包"
      }}</el-button>
    </div>
    <form class="protocol-query-tools" @submit.prevent="search()">
      <label
        >字典
        <el-select
          v-model="dictionary"
          aria-label="查询字典范围"
          @change="changeDictionary"
          ><el-option value="current" label="当前加载字典" /><el-option
            value="record"
            label="当前记录字典"
            :disabled="!capture" /></el-select
      ></label>
      <el-input
        v-model="query"
        aria-label="协议查询关键词"
        placeholder="协议名称、ID、字段或结构体；空格分隔多个词"
        :maxlength="200"
        clearable
      />
      <el-select v-model="scope" aria-label="协议查询范围" @change="search()"
        ><el-option value="all" label="全部范围" /><el-option
          value="name"
          label="协议名称 / ID" /><el-option
          value="field"
          label="字段（含嵌套）" /><el-option
          value="structure"
          label="结构体及引用"
      /></el-select>
      <el-select v-model="direction" aria-label="协议方向" @change="search()"
        ><el-option value="all" label="全部方向" /><el-option
          value="CS"
          label="发送 CS" /><el-option value="SC" label="接收 SC" /><el-option
          value="UNKNOWN"
          label="方向未知"
      /></el-select>
      <el-button native-type="submit" type="primary" :loading="busy"
        >查询</el-button
      >
    </form>
    <p v-if="error" class="error-bar" role="alert">
      {{ error }} <el-button text @click="search()">重试</el-button>
    </p>
    <p v-if="dictionaryChanged" class="warning-bar">
      当前加载字典已换版，页面仍显示上次查询结果。<el-button
        text
        @click="search()"
        >按新字典刷新</el-button
      >
    </p>
    <div v-if="catalog" class="catalog-meta muted">
      {{
        results?.captureId
          ? `记录字典 · ${capture?.name || results.captureId}`
          : "当前加载字典"
      }}
      · {{ catalog.messageCount }} 条协议 · {{ catalog.definitionCount }} 份定义
      <span :title="catalog.sha256"
        >SHA-256：{{
          catalog.sha256 ? catalog.sha256.slice(0, 12) : "旧字典未记录来源指纹"
        }}</span
      >
      <span :title="catalog.sourceFile">{{
        catalog.sourceFile || "无源码位置"
      }}</span>
    </div>
    <div v-if="results && !catalog?.definitionCount" class="query-empty">
      尚未加载协议字典。<el-button @click="emit('settings')"
        >加载协议</el-button
      >
    </div>
    <div v-else class="protocol-query-body">
      <div class="query-results" :aria-busy="busy">
        <el-table
          :data="results?.items || []"
          row-key="key"
          height="100%"
          highlight-current-row
          :current-row-key="selected?.key"
          @row-click="selectEntry"
          empty-text="没有匹配结果，可尝试名称片段或其他查询范围"
        >
          <el-table-column label="协议 / 结构体" min-width="200"
            ><template #default="{ row }"
              ><button
                class="protocol-result-name"
                @click.stop="selectEntry(row)"
              >
                {{ row.name }}
              </button>
              <div class="muted">
                {{
                  row.protocolId === undefined
                    ? "结构体"
                    : `ID ${row.protocolId} · ${row.direction}`
                }}
                · {{ row.fieldCount }} 字段
              </div>
              <div class="muted match-reason">
                {{ row.matchReason
                }}<span v-if="row.matchPath">
                  · {{ fieldPathLabel(row.matchPath) }}</span
                >
              </div></template
            ></el-table-column
          >
        </el-table>
        <el-pagination
          v-if="results"
          v-model:current-page="page"
          :page-size="PROTOCOL_QUERY_PAGE_SIZE"
          :total="results.total"
          layout="total, prev, pager, next"
          size="small"
          @current-change="search(false)"
        />
      </div>
      <div class="query-definition" :aria-busy="detailBusy">
        <p v-if="detailBusy" role="status" class="muted">读取定义…</p>
        <p v-if="detailError" role="alert" class="error-bar">
          {{ detailError }}
          <el-button text @click="search()">刷新查询</el-button>
        </p>
        <template v-if="selected">
          <div class="definition-heading">
            <el-button v-if="stack.length" text @click="back"
              >返回{{ stack.at(-1)?.label || "上级" }}</el-button
            >
            <h3>{{ selected.name }}</h3>
            <el-tag size="small">{{
              selected.kind === "message"
                ? `${selected.direction} · ID ${selected.protocolId}`
                : "结构体"
            }}</el-tag>
          </div>
          <div class="definition-actions">
            <span
              v-if="inlineCopy.feedback.value"
              class="muted"
              role="status"
              >{{ inlineCopy.feedback.value }}</span
            >
            <el-button size="small" @click="copy(selected.name)"
              >复制名称</el-button
            ><el-button
              v-if="selected.protocolId !== undefined"
              size="small"
              @click="copy(String(selected.protocolId))"
              >复制 ID</el-button
            ><el-button
              size="small"
              @click="copy(JSON.stringify(selected.rawDefinition, null, 2))"
              >复制本页定义 JSON</el-button
            ><el-button
              size="small"
              :disabled="!selected.sourceLine"
              @click="showSource"
              >查看源码</el-button
            ><el-button
              v-if="canShowMessages"
              size="small"
              type="primary"
              @click="
                emit('messages', {
                  captureId: selected.linkedCaptureId!,
                  protocolId: selected.protocolId!,
                })
              "
              >查看本记录消息（{{ selected.occurrences?.count }}）</el-button
            >
          </div>
          <p v-if="selected.occurrences" class="muted">
            {{ selected.occurrences.scope
            }}<span v-if="!selected.occurrences.count"
              >；本记录未捕获此协议</span
            >
          </p>
          <p v-else-if="selected.linkNotice" class="muted">
            {{ selected.linkNotice }}
          </p>
          <p v-if="matchPath" class="muted">
            命中路径：<code>{{ fieldPathLabel(matchPath) }}</code
            >，匹配的嵌套结构自动展开。
          </p>
          <h4>字段定义 <span class="muted">编号 · 名称 · 类型</span></h4>
          <ProtocolFieldTree
            :key="`${context.version}:${selected.key}:${selected.fieldOffset}`"
            :fields="selected.fields"
            :context="context"
            :lineage="[selected.key]"
            :match-path="matchPath"
            @open="open($event)"
            @copy="copy"
          />
          <el-pagination
            v-if="selected.fieldCount > selected.fieldPageSize"
            :current-page="fieldPage"
            :page-size="selected.fieldPageSize"
            :total="selected.fieldCount"
            layout="prev, pager, next"
            size="small"
            @current-change="changeFieldPage"
          />
          <h4>引用关系</h4>
          <p v-if="!selected.references.length" class="muted">
            没有其他定义直接引用此结构体。
          </p>
          <div class="reference-list">
            <el-button
              v-for="item in selected.references"
              :key="`${item.key}:${item.path}`"
              text
              @click="open(item.key, item.path)"
              >{{ item.name }} ·
              {{ fieldPathLabel(item.path || "") }}</el-button
            >
          </div>
          <p
            v-if="selected.referenceCount > selected.references.length"
            class="muted"
          >
            直接引用共 {{ selected.referenceCount }} 条，仅展示前
            {{ selected.references.length }} 条。
          </p>
          <template v-if="selected.usedByProtocolCount"
            ><h4>使用此结构的协议（{{ selected.usedByProtocolCount }}）</h4>
            <div class="reference-list">
              <el-button
                v-for="item in selected.usedByProtocols"
                :key="item.key"
                text
                @click="open(item.key, item.path)"
                >{{ item.name }}</el-button
              >
            </div>
            <p
              v-if="
                selected.usedByProtocolCount > selected.usedByProtocols.length
              "
              class="muted"
            >
              仅展示前
              {{ selected.usedByProtocols.length }}
              条，使用结构体名称搜索可分页查看全部。
            </p></template
          >
          <p class="muted">
            字段中文含义、必填性和枚举绑定未在字典中声明时不作推断；同名或相近协议不代表已确认的请求响应关系。
          </p>
          <section v-if="showingSource" class="protocol-source">
            <h4>定义源码</h4>
            <p v-if="sourceBusy" role="status">核对文件指纹并读取…</p>
            <p v-if="sourceError" class="error-bar">{{ sourceError }}</p>
            <template v-if="source"
              ><p>
                {{ source.path }}:{{ source.targetLine }} · 文件指纹已核对
                <el-button
                  text
                  @click="copy(`${source!.path}:${source!.targetLine}`)"
                  >复制位置</el-button
                >
              </p>
              <div
                v-for="line in source.lines"
                :key="line.line"
                :class="{ 'source-target': line.line === source.targetLine }"
              >
                <span class="muted">{{ line.line }}</span>
                <code
                  >{{ line.text
                  }}{{ line.lineTruncated ? " …（长行截断）" : "" }}</code
                >
              </div>
              <p class="muted">{{ source.note }}</p></template
            >
          </section>
        </template>
        <p v-else-if="!detailBusy && !detailError" class="query-empty">
          选择左侧结果查看字段、结构引用和源码。
        </p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.protocol-query-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}
.query-heading,
.protocol-query-tools,
.catalog-meta,
.definition-heading,
.definition-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.query-heading,
.protocol-query-tools,
.catalog-meta {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}
.query-heading > button {
  margin-left: auto;
}
.protocol-query-tools > .el-input {
  flex: 1;
  min-width: 220px;
}
.protocol-query-tools > .el-select {
  width: 155px;
}
.protocol-query-tools label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.protocol-query-tools label .el-select {
  width: 165px;
}
.catalog-meta {
  overflow-wrap: anywhere;
}
.protocol-query-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 34%) minmax(0, 1fr);
}
.query-results {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  border-right: 1px solid var(--border);
}
.query-results > .el-table {
  flex: 1;
  min-height: 0;
}
.query-results > .el-pagination {
  padding: 10px;
  flex-wrap: wrap;
}
.query-definition {
  min-width: 0;
  overflow: auto;
  padding: 14px;
}
.definition-heading h3 {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.definition-actions {
  margin: 12px 0;
}
.definition-actions .el-button,
.reference-list .el-button {
  margin-left: 0;
}
.reference-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}
.protocol-result-name {
  background: none;
  border: 0;
  padding: 0;
  color: var(--primary);
  text-align: left;
  word-break: break-word;
  font-family: monospace;
}
.match-reason {
  white-space: normal;
  word-break: break-word;
  margin-top: 4px;
}
.query-empty {
  padding: 24px;
  color: var(--muted);
}
.protocol-source {
  border-top: 1px solid var(--border);
  margin-top: 16px;
}
.protocol-source div {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.7;
}
.source-target {
  background: var(--soft);
}
@media (max-width: 1000px) {
  .protocol-query-body {
    display: flex;
    flex-direction: column;
    overflow: auto;
  }
  .query-results {
    flex: 0 0 300px;
    min-height: 300px;
    border-bottom: 1px solid var(--border);
  }
  .query-definition {
    flex: 0 0 auto;
    overflow: visible;
  }
}
</style>
