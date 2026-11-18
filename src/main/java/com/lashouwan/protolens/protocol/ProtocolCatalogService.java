package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 协议版本装配与发布；旧抓包保留原快照，运行中换版由实时服务锁阻止与解码交错。 */
public final class ProtocolCatalogService {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolCatalogService.class);
    private final ProtocolCatalog catalog;
    private final ProtocolExtractor extractor;
    private final AnalyzerSettingsStore settings;
    private final AnalyzerLaunchOptions options;
    private final ObjectMapper mapper;

    /** 最近启动自动加载失败原因，仅为诊断状态，不写入用户配置。 */
    private volatile String lastError = "";

    public ProtocolCatalogService(
            AnalyzerLaunchOptions options,
            AnalyzerSettingsStore settings,
            ProtocolCatalog catalog,
            ObjectMapper mapper) {
        this.options = options;
        this.settings = settings;
        this.catalog = catalog;
        this.mapper = mapper;
        this.extractor = new ProtocolExtractor(options, mapper);
    }

    /** 默认来源不存在或提取失败时保留 HTTP 配置入口，不把失败静默伪装为已加载。 */
    public void initialize() throws Exception {
        if (!options.config().protocolAutoLoad()) {
            return;
        }
        ObjectNode snapshot = settings.snapshot();
        String source = snapshot.path("competitorPath").asText();
        if (source.isBlank()) {
            return;
        }
        try {
            reload(source, snapshot.path("protocolFile").asText());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            lastError = failure.getMessage();
            logger.warn("启动加载协议失败，请在页面手动选择来源 reason:{}", lastError);
        }
    }

    public ObjectNode discover(String source) throws Exception {
        return extractor.discover(source);
    }

    public String lastError() {
        return lastError;
    }

    /** 提取、校验完成后才发布；文件发布失败保留当前内存字典，用户来源可供下次重试。 */
    public synchronized ObjectNode reload(String source, String selectedFile) throws Exception {
        ObjectNode discovery = extractor.discover(source);
        JsonNode candidates = discovery.path("candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("未找到 thrift_data.js，请检查目录或直接填写文件路径");
        }
        Path root = Path.of(discovery.path("root").asText());
        JsonNode selected = null;
        if (selectedFile.isBlank() && candidates.size() == 1) {
            selected = candidates.get(0);
        } else if (!selectedFile.isBlank()) {
            Path requested = root.resolve(selectedFile).toAbsolutePath().normalize();
            for (JsonNode candidate : candidates) {
                if (requested.equals(Path.of(candidate.path("path").asText()))) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("选择的协议文件不在当前扫描结果中，请重新查找和选择");
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("找到多个 thrift_data.js，请先查找并选择要加载的文件");
        }
        Path file = Path.of(selected.path("path").asText());
        Path temporary = Files.createTempFile(options.dataDirectory(), "catalog-", ".json");
        try {
            extractor.extract(file, root, temporary);
            ProtocolCatalog replacement = ProtocolCatalog.load(temporary, mapper);
            if (replacement.summary(mapper).path("messageCount").asInt() == 0) {
                throw new IOException("提取结果没有协议，旧字典保持不变");
            }
            String input = discovery.path("input").asText();
            String relative =
                    Files.isDirectory(Path.of(input)) ? selected.path("relativePath").asText() : "";
            settings.saveProtocolSource(input, relative, file.toString());
            Files.move(
                    temporary,
                    options.dataDirectory().resolve("catalog.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            catalog.replace(replacement.snapshot());
            lastError = "";
            return catalog.summary(mapper);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
