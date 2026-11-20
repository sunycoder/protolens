<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api, type Capture, type Packet } from "./types";
import {
  analysisParams,
  compareOperations,
  type FieldColumn,
  type MessageNote,
  type RangeAnalysis,
} from "./analysisTools";
import type { WorkspaceQuery } from "./workspaceInteractions";
import { RequestGate } from "./workspaceRefresh";

const props = defineProps<{
  capture: Capture;
  captures: Capture[];
  query: WorkspaceQuery;
  columns: FieldColumn[];
  notes: MessageNote[];
  mode?: string;
  initialRange?: { from: number; to: number };
  selected?: Packet;
}>();
const emit = defineEmits<{
  close: [];
  locate: [value: { captureId: string; index: number; path?: string }];
  range: [value: { from: number; to: number; index?: number; name: string }];
  removeColumn: [path: string];
  removeNote: [note: MessageNote];
  note: [path: string];
  watchField: [field: FieldColumn];
  saveRange: [];
}>();
const mode = ref(props.mode || "review"),
  busy = ref(false),
  error = ref("");
const gate = new RequestGate();
const review = ref<RangeAnalysis>();
const resultA = ref<RangeAnalysis>(),
  resultB = ref<RangeAnalysis>();
const rangeName = ref(
  props.initialRange && (props.mode || "review") === "review"
    ? "operation"
    : "current",
);
const clock = ref(""),
  seconds = ref(3);
const rangeA = ref({
  captureId: props.capture.id,
  from: props.capture.firstIndex || 0,
  to: props.capture.lastIndex || 0,
});
const rangeB = ref({ ...rangeA.value });
const marksA = ref<Capture>(props.capture),
  marksB = ref<Capture>(props.capture);
const markA = ref<number>(),
  markB = ref<number>();
const comparison = computed(() =>
  resultA.value && resultB.value
    ? compareOperations(resultA.value, resultB.value)
    : [],
);
const fieldComparison = computed(() =>
  props.columns.map((column) => ({
    path: column.path,
    before:
      resultA.value?.fields.find((field) => field.path === column.path)?.last ||
      "—",
    after:
      resultB.value?.fields.find((field) => field.path === column.path)?.last ||
      "—",
  })),
);
watch(mode, () => {
  gate.cancel();
  busy.value = false;
  error.value = "";
  review.value = undefined;
});
onMounted(() => {
  if (mode.value === "review" || mode.value === "anomalies") void loadReview();
});
watch(
  () => props.capture.id,
  () => {
    gate.cancel();
    review.value = undefined;
    resultA.value = undefined;
    resultB.value = undefined;
    busy.value = false;
    error.value = "记录已切换，请重新生成分析";
  },
);
onBeforeUnmount(() => gate.cancel());
watch(
  () =>
    JSON.stringify([
      props.query,
      props.columns,
      rangeName.value,
      rangeA.value,
      rangeB.value,
    ]),
  () => {
    gate.cancel();
    busy.value = false;
    review.value = undefined;
    resultA.value = undefined;
    resultB.value = undefined;
    error.value = "";
  },
);
async function loadReview() {
  const request = gate.begin(),
    record = props.capture;
  busy.value = true;
  error.value = "";
  try {
    const mark = rangeName.value.startsWith("mark:")
      ? record.marks[Number(rangeName.value.slice(5))]
      : rangeName.value === "operation"
        ? props.initialRange
        : undefined;
    const query =
      mode.value === "anomalies"
        ? { ...props.query, status: "ATTENTION" }
        : props.query;
    const result = await api<RangeAnalysis>(
      `/api/captures/${record.id}/analysis?${analysisParams(query, record, props.columns, mark)}`,
      { signal: request.signal },
    );
    if (request.current() && props.capture.id === record.id)
      review.value = result;
  } catch (cause) {
    if (request.current()) error.value = String(cause);
  } finally {
    if (request.current()) busy.value = false;
  }
}
async function selectComparison(side: "A" | "B") {
  const range = side === "A" ? rangeA : rangeB;
  const id = range.value.captureId;
  try {
    const record = await api<Capture>(`/api/captures/${id}/overview`);
    if (range.value.captureId !== id) return;
    (side === "A" ? marksA : marksB).value = record;
    range.value = {
      captureId: id,
      from: record.firstIndex || 0,
      to: record.lastIndex || 0,
    };
    (side === "A" ? markA : markB).value = undefined;
  } catch (cause) {
    error.value = String(cause);
  }
}
function chooseMark(side: "A" | "B") {
  const mark = (side === "A" ? marksA.value : marksB.value).marks[
    (side === "A" ? markA.value : markB.value) ?? -1
  ];
  if (mark)
    Object.assign((side === "A" ? rangeA : rangeB).value, {
      from: mark.from,
      to: mark.to,
    });
}
async function compare() {
  const request = gate.begin();
  busy.value = true;
  error.value = "";
  resultA.value = undefined;
  resultB.value = undefined;
  try {
    const values = await Promise.all(
      [rangeA.value, rangeB.value].map(async (range) => {
        if (
          !Number.isSafeInteger(range.from) ||
          !Number.isSafeInteger(range.to) ||
          range.from < 0 ||
          range.to < range.from
        )
          throw new Error("操作区间必须是有效的原消息编号");
        const record =
          range.captureId === marksA.value.id ? marksA.value : marksB.value;
        // 两次操作对照明确限定区间，保留用户选择的协议、方向、心跳等筛选。
        return api<RangeAnalysis>(
          `/api/captures/${range.captureId}/analysis?${analysisParams(props.query, record, props.columns, range)}`,
          { signal: request.signal },
        );
      }),
    );
    if (request.current()) {
      resultA.value = values[0];
      resultB.value = values[1];
    }
  } catch (cause) {
    if (request.current()) error.value = String(cause);
  } finally {
    if (request.current()) busy.value = false;
  }
}
async function locateTime() {
  const request = gate.begin(),
    record = props.capture;
  busy.value = true;
  error.value = "";
  try {
    const params = analysisParams(props.query, record, []);
    params.set("time", clock.value.trim());
    params.set("seconds", String(seconds.value));
    const result = await api<{
      found: boolean;
      index: number;
      from: number;
      to: number;
      time: string;
      distanceMs: number;
      ties: number;
      truncated: boolean;
    }>(`/api/captures/${record.id}/time-location?${params}`, {
      signal: request.signal,
    });
    if (!request.current()) return;
    if (!result.found) {
      error.value = "当前筛选和保留范围内没有可定位的时间";
      return;
    }
    if (result.ties > 1 || result.truncated)
      error.value = `${result.ties > 1 ? "存在重复时刻，定位最早保留的匹配消息。" : ""}${result.truncated ? "扫描已达上限，结果仅覆盖已检查范围。" : ""}`;
    emit("range", {
      from: result.from,
      to: result.to,
      index: result.index,
      name: `${result.time} 前后 ${seconds.value} 秒（距输入 ${result.distanceMs}ms）${result.ties > 1 ? " · 重复时刻，取最早保留消息" : ""}${result.truncated ? " · 扫描范围不完整" : ""}`,
    });
  } catch (cause) {
    if (request.current()) error.value = String(cause);
  } finally {
    if (request.current()) busy.value = false;
  }
}
function useSelectedTime() {
  const match = props.selected?.time.match(
    /(?:^|[T ])(\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?)/,
  );
  if (match) clock.value = match[1];
  else error.value = "选中消息没有可识别的时钟时间";
}
function exportNotes() {
  const url = URL.createObjectURL(
    new Blob(
      [
        JSON.stringify(
          {
            captureId: props.capture.id,
            dictionary: props.capture.catalog?.sha256,
            notes: props.notes,
          },
          null,
          2,
        ),
      ],
      { type: "application/json" },
    ),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = "protolens-message-notes.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function exportReview() {
  if (!review.value) return;
  const blob = new Blob(
    [
      JSON.stringify(
        {
          kind: "ProtoLens 操作回顾",
          scope: "保留范围、当前筛选，字段为首次末次采样，不推断因果",
          ...review.value,
        },
        null,
        2,
      ),
    ],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob),
    link = document.createElement("a");
  link.href = url;
  link.download = "protolens-operation-review.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
</script>
<template>
  <el-drawer
    :model-value="true"
    title="分析工具"
    size="min(960px, 96vw)"
    @close="emit('close')"
  >
    <el-tabs v-model="mode">
      <el-tab-pane label="时间定位" name="time" /><el-tab-pane
        label="操作回顾"
        name="review"
      /><el-tab-pane label="两次操作对照" name="compare" /><el-tab-pane
        label="异常协议"
        name="anomalies"
      /><el-tab-pane label="批注" name="notes" /><el-tab-pane
        label="字段列"
        name="columns"
      />
    </el-tabs>
    <p v-if="error" class="warning-bar" role="status">{{ error }}</p>
    <template v-if="mode === 'time'">
      <p class="muted">
        按记录原始时间定位最近消息，继续应用当前筛选。时间没有日期信息时不推断日期或时区；重复时刻定位最早保留消息。
      </p>
      <div class="research-controls">
        <el-input
          v-model="clock"
          placeholder="HH:mm:ss 或 HH:mm:ss.SSS"
          aria-label="定位时间"
          @keyup.enter="locateTime"
        /><el-input-number
          v-model="seconds"
          :min="0"
          :max="60"
          aria-label="前后秒数"
        /><span>前后秒数（0 为仅该条）</span
        ><el-button type="primary" :loading="busy" @click="locateTime"
          >定位时间</el-button
        ><el-button v-if="selected" @click="useSelectedTime"
          >使用选中消息时间</el-button
        >
      </div>
    </template>
    <template v-if="mode === 'review' || mode === 'anomalies'">
      <p class="muted">
        只在点击时生成，使用当前筛选和保留范围。异常面板将状态限定为部分解码、失败和未映射；操作回顾不推断请求响应或因果关系。
      </p>
      <div class="research-controls">
        <el-select v-model="rangeName" aria-label="分析区间"
          ><el-option label="当前查看范围" value="current" /><el-option
            v-if="initialRange"
            label="刚结束的操作"
            value="operation" /><el-option
            v-for="(mark, index) in capture.marks"
            :key="index"
            :label="`${mark.name} · #${mark.from}–${mark.to}`"
            :value="`mark:${index}`" /></el-select
        ><el-button type="primary" :loading="busy" @click="loadReview"
          >生成分析</el-button
        ><el-button :disabled="!review" @click="exportReview"
          >导出回顾</el-button
        >
        <el-button v-if="initialRange" @click="emit('saveRange')"
          >命名 / 保存操作区间</el-button
        >
      </div>
      <template v-if="review">
        <p>
          匹配 {{ review.total }} 条 · {{ review.groups.length }} 类协议 / 方向
          · 原区间 #{{ review.from }}–{{ review.to }}
        </p>
        <p
          v-if="
            review.missingBefore || review.truncated || review.timelineTruncated
          "
          class="warning-bar"
        >
          {{ review.missingBefore ? "区间前部已不在保留范围。" : ""
          }}{{
            review.truncated
              ? "扫描或分组已达上限，数量仅覆盖已检查范围。"
              : ""
          }}{{
            review.timelineTruncated
              ? "时间线最多展示前 2000 条，统计与时间线范围不同。"
              : ""
          }}
        </p>
        <el-table
          :data="mode === 'anomalies' ? review.anomalies : review.groups"
          max-height="300"
          empty-text="当前范围没有匹配协议"
          ><el-table-column
            prop="protocolName"
            label="协议"
            min-width="190"
            show-overflow-tooltip
          /><el-table-column
            prop="direction"
            label="方向"
            width="65"
          /><el-table-column
            v-if="mode === 'anomalies'"
            prop="status"
            label="状态"
            width="110"
          /><el-table-column
            prop="count"
            label="次数"
            width="75"
          /><el-table-column label="首次 / 样本" width="120"
            ><template #default="{ row }"
              ><el-button
                text
                @click="
                  emit('locate', {
                    captureId: capture.id,
                    index: row.firstIndex,
                  })
                "
                >消息 #{{ row.firstIndex }}</el-button
              ></template
            ></el-table-column
          ></el-table
        >
        <template v-if="mode === 'review'"
          ><p>
            字段首次 /
            末次实际出现值（使用已添加的字段列；同路径可能来自不同协议，请核对证据）
          </p>
          <el-table :data="review.fields" max-height="220"
            ><el-table-column
              prop="path"
              label="字段路径"
              min-width="160"
            /><el-table-column
              prop="first"
              label="首次值"
              min-width="140"
            /><el-table-column
              prop="last"
              label="末次值"
              min-width="140"
            /><el-table-column label="变化" width="70"
              ><template #default="{ row }">{{
                row.changed ? "有变化" : "—"
              }}</template></el-table-column
            ></el-table
          >
          <details>
            <summary>消息顺序（原编号）</summary>
            <ol class="research-timeline">
              <li v-for="item in review.timeline" :key="item.index">
                <button
                  @click="
                    emit('locate', { captureId: capture.id, index: item.index })
                  "
                >
                  #{{ item.index }} · {{ item.time }} · {{ item.direction }} ·
                  {{ item.protocolName }}
                </button>
              </li>
            </ol>
          </details></template
        >
      </template>
    </template>
    <template v-if="mode === 'compare'">
      <p class="muted">
        选择两次操作的记录与原消息区间，可从已有标记填入。继续应用当前协议等筛选；跨记录的连接
        ID
        可能不同，必要时先解除连接筛选。次数、顺序和字段采样不代表自动匹配请求响应。
      </p>
      <div
        v-for="side in ['A', 'B'] as const"
        :key="side"
        class="research-comparison-range"
      >
        <b>操作 {{ side }}</b
        ><el-select
          v-model="(side === 'A' ? rangeA : rangeB).captureId"
          :aria-label="`操作${side}记录`"
          @change="selectComparison(side)"
          ><el-option
            v-for="record in captures"
            :key="record.id"
            :label="record.name"
            :value="record.id"
        /></el-select>
        <el-select
          v-if="side === 'A'"
          v-model="markA"
          placeholder="从标记填入"
          clearable
          @change="chooseMark('A')"
          ><el-option
            v-for="(mark, index) in marksA.marks"
            :key="index"
            :label="mark.name"
            :value="index" /></el-select
        ><el-select
          v-else
          v-model="markB"
          placeholder="从标记填入"
          clearable
          @change="chooseMark('B')"
          ><el-option
            v-for="(mark, index) in marksB.marks"
            :key="index"
            :label="mark.name"
            :value="index"
        /></el-select>
        <el-input-number
          v-model="(side === 'A' ? rangeA : rangeB).from"
          :min="0"
          :precision="0"
          :aria-label="`操作${side}起点`"
        /><span>至</span
        ><el-input-number
          v-model="(side === 'A' ? rangeA : rangeB).to"
          :min="0"
          :precision="0"
          :aria-label="`操作${side}终点`"
        />
      </div>
      <el-button type="primary" :loading="busy" @click="compare"
        >对照两次操作</el-button
      >
      <template v-if="resultA && resultB"
        ><p>A {{ resultA.total }} 条 / B {{ resultB.total }} 条</p>
        <p v-if="resultA.dictionary !== resultB.dictionary" class="warning-bar">
          两份记录的协议字典不同，字段和协议含义可能变化，请分别核对定义。
        </p>
        <p
          v-if="
            resultA.missingBefore ||
            resultB.missingBefore ||
            resultA.truncated ||
            resultB.truncated
          "
          class="warning-bar"
        >
          存在保留范围缺失或扫描截断，以下为可用范围对照。
        </p>
        <el-table :data="comparison" max-height="270"
          ><el-table-column
            prop="name"
            label="协议"
            min-width="170"
          /><el-table-column
            prop="direction"
            label="方向"
            width="65"
          /><el-table-column
            prop="before"
            label="A 次数"
            width="85"
          /><el-table-column
            prop="after"
            label="B 次数"
            width="85"
          /><el-table-column label="差值" width="85"
            ><template #default="{ row }">{{
              row.after - row.before
            }}</template></el-table-column
          ></el-table
        >
        <p>字段末次采样值</p>
        <el-table :data="fieldComparison" max-height="180"
          ><el-table-column prop="path" label="字段" /><el-table-column
            prop="before"
            label="A" /><el-table-column prop="after" label="B"
        /></el-table>
        <details>
          <summary>对照消息顺序（按原编号，不按协议名配对）</summary>
          <div class="research-sequences">
            <div
              v-for="result in [resultA, resultB]"
              :key="result.captureId + ':' + result.from + ':' + result.to"
            >
              <p v-if="result.timelineTruncated">仅展示前 2000 条</p>
              <ol class="research-timeline">
                <li v-for="item in result.timeline" :key="item.index">
                  <button
                    @click="
                      emit('locate', {
                        captureId: result.captureId,
                        index: item.index,
                      })
                    "
                  >
                    #{{ item.index }} · {{ item.time }} · {{ item.direction }} ·
                    {{ item.protocolName }}
                  </button>
                </li>
              </ol>
            </div>
          </div>
        </details></template
      >
    </template>
    <template v-if="mode === 'notes'">
      <el-button :disabled="!notes.length" @click="exportNotes"
        >导出本记录批注</el-button
      >
      <p class="muted">
        批注仅保存在本机，按记录和原消息编号绑定，不修改消息。消息已淘汰时仍保留批注，但不能恢复原内容。
      </p>
      <el-button :disabled="!selected" @click="emit('note', '')"
        >为当前消息批注</el-button
      >
      <div
        v-for="note in notes"
        :key="`${note.index}:${note.path}`"
        class="research-note"
      >
        <b>{{ note.kind }}</b> · #{{ note.index }} {{ note.path || "消息" }}
        <p>{{ note.text }}</p>
        <el-button
          text
          @click="
            emit('locate', {
              captureId: capture.id,
              index: note.index,
              path: note.path,
            })
          "
          >定位证据</el-button
        ><el-button text type="danger" @click="emit('removeNote', note)"
          >删除批注</el-button
        >
      </div>
      <p v-if="!notes.length" class="muted">
        尚无批注，可在消息详情或字段右键添加。
      </p></template
    >
    <template v-if="mode === 'columns'"
      ><p class="muted">
        在 JSON 字段右键或点击 ⋯ 添加字段列。按字典版本保存，最多 6
        列；缺失字段显示 —，容器显示项数。
      </p>
      <div v-for="field in columns" :key="field.path" class="research-note">
        <b>{{ field.label }}</b
        ><code>{{ field.path }}</code
        ><el-button text @click="emit('watchField', field)"
          >关注此字段变化</el-button
        ><el-button text type="danger" @click="emit('removeColumn', field.path)"
          >移除列</el-button
        >
      </div></template
    >
  </el-drawer>
</template>
