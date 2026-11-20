import { nextTick, ref } from "vue";
import type { ShallowRef } from "vue";
import { ElMessage } from "element-plus";
import { MAX_UPLOAD_BYTES } from "./constants.ts";
import { api, post, type Capture } from "./types.ts";
import { useProjectSync, type ProjectDocument } from "./useProjectSync.ts";
/** 文件入口与记录成果同步共用同一版本状态；导入始终创建新记录。 */
export function useAnalysisProject(options: {
  capture: ShallowRef<Capture | undefined>;
  enabled: () => boolean;
  document: () => Omit<ProjectDocument, "revision">;
  apply: (document: ProjectDocument) => void;
  reloadCaptures: () => Promise<void>;
  openCapture: (capture: Capture) => Promise<void>;
  failure: (error: unknown) => void;
}) {
  const projectSync = useProjectSync({
    id: () => options.capture.value?.id,
    enabled: options.enabled,
    document: options.document,
    apply: options.apply,
  });
  const failure = options.failure;
  const projectInput = ref<HTMLInputElement>();
  const projectBusy = ref(false);
  async function exportAnalysisProject() {
    if (!options.capture.value || projectBusy.value) return;
    projectBusy.value = true;
    try {
      await nextTick();
      try {
        await projectSync.flush();
      } catch {
        ElMessage.warning(
          "成果尚未写入记录，本次导出会包含当前未保存的分析输入",
        );
      }
      const id = options.capture.value.id;
      const document = await api<Record<string, unknown>>(
        `/api/captures/${id}/project`,
      );
      document.workspace = {
        revision: projectSync.revision.value,
        ...options.document(),
      };
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(document)], { type: "application/json" }),
      );
      const link = window.document.createElement("a");
      link.href = url;
      link.download = "ProtoLens-analysis-project.json";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      failure(error);
    } finally {
      projectBusy.value = false;
    }
  }
  async function importAnalysisProject(event: Event) {
    const input = event.target as HTMLInputElement,
      file = input.files?.[0];
    if (!file) return;
    projectBusy.value = true;
    try {
      if (file.size > MAX_UPLOAD_BYTES) throw new Error("分析项目超过允许大小");
      const document = JSON.parse(await file.text());
      const record = await post<Capture>("/api/import/project", document);
      await options.reloadCaptures();
      await options.openCapture(record);
      ElMessage.success("分析项目已导入为新记录，批注和字段列随记录恢复");
    } catch (error) {
      failure(error);
    } finally {
      input.value = "";
      projectBusy.value = false;
    }
  }

  return {
    projectSync,
    projectInput,
    projectBusy,
    exportAnalysisProject,
    importAnalysisProject,
  };
}
