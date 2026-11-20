import { ref } from "vue";
import { ElMessage } from "element-plus";
import { CACHE_BUDGET } from "./constants.ts";
import { api, post } from "./types.ts";
export interface CatalogSummary {
  messageCount: number;
  definitionCount: number;
  sha256: string;
  sourceFile: string;
  sourceRoot: string;
}
/** 设置、候选协议与版本检查独立持有状态，成功加载后才通知工作台更换字典摘要。 */
export function useWorkbenchSettings(options: {
  catalogLoaded: (catalog: CatalogSummary) => void;
  failure: (error: unknown) => void;
}) {
  const failure = options.failure;
  const settingsDialog = ref(false);
  const settingsTab = ref("protocol");
  const settingsBusy = ref(false);
  const update = ref({
    currentVersion: "",
    latestVersion: "",
    downloadUrl: "",
    fileName: "",
    available: false,
    releaseUrl: "",
  });
  const settings = ref({
    serverPort: 0,
    frontendPort: 0,
    backendAddress: "",
    browserAddress: "",
    proxyAddress: "",
    configFile: "",
    configPrecedence: "",
    competitorPath: "",
    protocolFile: "",
    loadedProtocolFile: "",
    maxMessages: CACHE_BUDGET.defaultMessages,
    maxCacheMiB: CACHE_BUDGET.defaultMiB,
    collectorPort: 18888,
    autoStartCollector: true,
    catalogError: "",
  });
  interface ProtocolCandidate {
    path: string;
    relativePath: string;
    sizeBytes: number;
    modifiedAt: string;
  }
  const protocolCandidates = ref<ProtocolCandidate[]>([]);
  const selectedProtocolFile = ref("");
  function sourceChanged() {
    protocolCandidates.value = [];
    selectedProtocolFile.value = "";
  }
  async function scanProtocolFiles() {
    const result = await post<{ candidates: ProtocolCandidate[] }>(
      "/api/catalog/discover",
      { path: settings.value.competitorPath },
    );
    protocolCandidates.value = result.candidates;
    const chosen = result.candidates.find(
      (item) =>
        item.path === selectedProtocolFile.value ||
        item.relativePath === selectedProtocolFile.value,
    );
    selectedProtocolFile.value =
      chosen?.path ||
      (result.candidates.length === 1 ? result.candidates[0].path : "");
    if (!result.candidates.length)
      ElMessage.warning(
        "没有找到 thrift_data.js，请检查路径或直接填写文件路径",
      );
  }
  async function findProtocolFiles() {
    settingsBusy.value = true;
    try {
      await scanProtocolFiles();
    } catch (error) {
      failure(error);
    } finally {
      settingsBusy.value = false;
    }
  }
  async function openSettings() {
    try {
      settings.value = await api("/api/settings");
      protocolCandidates.value = [];
      selectedProtocolFile.value = settings.value.protocolFile || "";
      settingsDialog.value = true;
    } catch (error) {
      failure(error);
    }
  }
  async function checkUpdate() {
    try {
      update.value = await api("/api/update");
      if (update.value.available)
        ElMessage.success(`发现 ProtoLens ${update.value.latestVersion}`);
      else ElMessage.info("当前已是最新版本");
    } catch (error) {
      failure(error);
    }
  }
  async function saveSettings() {
    settingsBusy.value = true;
    try {
      settings.value = await post("/api/settings", settings.value);
      ElMessage.success("缓存设置已保存，新连接生效");
    } catch (error) {
      failure(error);
    } finally {
      settingsBusy.value = false;
    }
  }
  async function reloadCatalog() {
    settingsBusy.value = true;
    try {
      await scanProtocolFiles();
      if (!selectedProtocolFile.value) {
        if (protocolCandidates.value.length > 1)
          ElMessage.warning("找到多个协议文件，请从下拉列表选择要加载的一份");
        return;
      }
      const catalog = await post<CatalogSummary>("/api/catalog/reload", {
        path: settings.value.competitorPath,
        file: selectedProtocolFile.value,
      });
      options.catalogLoaded(catalog);
      settings.value = await api("/api/settings");
      ElMessage.success("已加载协议，新连接使用新字典；已有记录保持原字典");
    } catch (error) {
      failure(error);
    } finally {
      settingsBusy.value = false;
    }
  }

  return {
    settingsDialog,
    settingsTab,
    settingsBusy,
    update,
    settings,
    protocolCandidates,
    selectedProtocolFile,
    sourceChanged,
    findProtocolFiles,
    openSettings,
    checkUpdate,
    saveSettings,
    reloadCatalog,
  };
}
