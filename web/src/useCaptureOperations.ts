import { ref, nextTick, type Ref, type ShallowRef } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { MAX_CAPTURE_NAME_LENGTH } from "./constants.ts";
import { api, post, type Capture, type Packet } from "./types.ts";
import { defaultQuery, type WorkspaceQuery } from "./workspaceInteractions.ts";
/** 操作起止、标记、快照与持续保存共用记录边界，异步完成仍核对源记录 ID。 */
export function useCaptureOperations(options: {
  capture: ShallowRef<Capture | undefined>;
  busy: Ref<boolean>;
  selected: () => Packet | undefined;
  projectEnabled: () => boolean;
  supportsAnalysis: () => boolean;
  flushProject: () => Promise<void>;
  reloadCaptures: () => Promise<void>;
  openCapture: (capture: Capture) => Promise<void>;
  applyQuery: (query: WorkspaceQuery) => void;
  quickQuery: (query: Partial<WorkspaceQuery>) => Promise<void>;
  openResearch: (mode?: string) => void;
  failure: (error: unknown) => void;
}) {
  const {
    capture,
    busy,
    reloadCaptures,
    openCapture,
    applyQuery,
    quickQuery,
    openResearch,
    failure,
  } = options;
  const lastOperationRange = ref<{ from: number; to: number }>();
  const snapshotBusy = ref(false);
  const continuousBusy = ref(false);
  const savedSnapshot = ref<Capture>();
  const operation = ref({ name: "", from: 0, to: 0, context: "" });
  const markDialog = ref(false);
  const operationStart = ref<{ captureId: string; index: number }>();
  async function beginOperation() {
    if (!capture.value) return;
    const id = capture.value.id;
    try {
      const item = await api<Capture>(`/api/captures/${id}/overview`);
      if (capture.value?.id !== id) return;
      operationStart.value = {
        captureId: item.id,
        index: (item.lastIndex ?? -1) + 1,
      };
      ElMessage.success("操作起点已记录，现在去游戏执行操作，再点击结束");
    } catch (error) {
      failure(error);
    }
  }
  async function endOperation() {
    if (
      !capture.value ||
      !operationStart.value ||
      operationStart.value.captureId !== capture.value.id
    )
      return;
    const id = capture.value.id;
    try {
      const item = await api<Capture>(`/api/captures/${id}/overview`);
      if (capture.value?.id !== id) return;
      if ((item.lastIndex ?? -1) < operationStart.value.index) {
        ElMessage.warning("操作后尚未收到消息，请稍后重试");
        return;
      }
      capture.value = item;
      operation.value = {
        name: "操作分析 · " + new Date().toLocaleString("sv-SE"),
        from: operationStart.value.index,
        to: item.lastIndex!,
        context: "",
      };
      lastOperationRange.value = {
        from: operation.value.from,
        to: operation.value.to,
      };
      operationStart.value = undefined;
      if (options.supportsAnalysis()) {
        markDialog.value = false;
        openResearch("review");
      } else markDialog.value = true;
    } catch (error) {
      failure(error);
    }
  }
  async function freezeOperation() {
    if (!capture.value || busy.value) return;
    const id = capture.value.id;
    busy.value = true;
    try {
      if (options.projectEnabled()) {
        await nextTick();
        await options.flushProject();
      }
      const frozen = await post<Capture>(
        `/api/captures/${id}/snapshot`,
        operation.value,
      );
      operationStart.value = undefined;
      markDialog.value = false;
      await reloadCaptures();
      if (capture.value?.id === id) {
        applyQuery({ ...defaultQuery, hideHeartbeat: false });
        await openCapture(frozen);
      }
      ElMessage.success(
        "分析快照已保存，原连接继续采集，可将本记录交给 AI 查询",
      );
    } catch (error) {
      failure(error);
    } finally {
      busy.value = false;
    }
  }
  async function saveCurrentSnapshot() {
    const source = capture.value;
    if (!source || snapshotBusy.value) return;
    try {
      const result = await ElMessageBox.prompt(
        "保存当前仍保留的全部消息，源连接继续采集；已淘汰消息不能补回。",
        "保存此刻",
        {
          inputValue: source.name.slice(0, 70) + " · 快照",
          inputValidator: (value) =>
            (!!value?.trim() && value.length <= MAX_CAPTURE_NAME_LENGTH) ||
            "请输入 1～100 字名称",
        },
      );
      snapshotBusy.value = true;
      if (capture.value?.id !== source.id) return;
      if (options.projectEnabled()) {
        await nextTick();
        await options.flushProject();
      }
      savedSnapshot.value = await post<Capture>(
        `/api/captures/${source.id}/snapshot-current`,
        { name: result.value },
      );
      await reloadCaptures();
      ElMessage.success("快照已保存，当前阅读位置和源连接保持");
    } catch (error) {
      if (error !== "cancel" && error !== "close") failure(error);
    } finally {
      snapshotBusy.value = false;
    }
  }
  async function enableContinuous() {
    const source = capture.value;
    if (!source || continuousBusy.value) return;
    continuousBusy.value = true;
    try {
      const result = await post<Capture>(
        `/api/captures/${source.id}/continuous`,
        {},
      );
      if (capture.value?.id === source.id) capture.value = result;
      await reloadCaptures();
      ElMessage.success(
        "已开始持续保存；内存继续按预算淘汰，磁盘记录可在停止或重启后读取",
      );
    } catch (error) {
      failure(error);
    } finally {
      continuousBusy.value = false;
    }
  }
  async function mark() {
    if (!capture.value || busy.value) return;
    const id = capture.value.id;
    busy.value = true;
    try {
      const marked = await post<Capture>(
        `/api/captures/${id}/marks`,
        operation.value,
      );
      if (capture.value?.id === id) {
        capture.value = marked;
        operationStart.value = undefined;
        await quickQuery({ range: String(marked.marks.length) });
      }
      markDialog.value = false;
      await reloadCaptures();
    } catch (error) {
      failure(error);
    } finally {
      busy.value = false;
    }
  }
  function openMark() {
    operation.value = {
      name: "",
      context: "",
      from: options.selected()?.index ?? capture.value?.firstIndex ?? 0,
      to:
        options.selected()?.index ??
        capture.value?.lastIndex ??
        Math.max(0, (capture.value?.messageCount || 1) - 1),
    };
    markDialog.value = true;
  }
  async function exportCapture() {
    if (!capture.value) return;
    try {
      const data = await api(`/api/captures/${capture.value.id}/export`);
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download =
        capture.value.name.replace(/\.saz$/i, "") + "-decoded.json";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      failure(error);
    }
  }

  return {
    operation,
    markDialog,
    operationStart,
    lastOperationRange,
    snapshotBusy,
    continuousBusy,
    savedSnapshot,
    beginOperation,
    endOperation,
    freezeOperation,
    saveCurrentSnapshot,
    enableContinuous,
    mark,
    openMark,
    exportCapture,
  };
}
