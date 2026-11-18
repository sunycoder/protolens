package com.lashouwan.protolens.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** 采集目标仍只写启动配置；展示待生效值与实际值，不在 settings.json 再保存第二份。 */
public final class CollectorConfigStore {
    private final AnalyzerLaunchOptions options;
    private final ObjectMapper mapper;

    public CollectorConfigStore(AnalyzerLaunchOptions options, ObjectMapper mapper) {
        this.options = options;
        this.mapper = mapper;
    }

    public synchronized ObjectNode snapshot() throws IOException {
        return describe(AnalyzerConfig.from(readConfig()).captureTargets());
    }

    /** 先校验完整候选配置，再原子替换；保留端口等其余字段，失败不改磁盘或运行中的规则。 */
    public synchronized ObjectNode update(JsonNode input) throws IOException {
        if (!input.has("captureHosts") || !input.has("capturePathPrefixes")) {
            throw new IllegalArgumentException("请同时提供采集域名和 WebSocket 路径前缀");
        }
        var names = input.fieldNames();
        while (names.hasNext()) {
            if (!Set.of("captureHosts", "capturePathPrefixes").contains(names.next())) {
                throw new IllegalArgumentException("此入口只允许修改采集域名和路径");
            }
        }
        CaptureTargets targets = CaptureTargets.from(input);
        ObjectNode next = readConfig();
        next.set("captureHosts", mapper.valueToTree(targets.hosts()));
        next.set("capturePathPrefixes", mapper.valueToTree(targets.pathPrefixes()));
        AnalyzerConfig.from(next);
        Path file = options.configFile().toAbsolutePath();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "collector-config-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), next);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return describe(targets);
    }

    private ObjectNode readConfig() throws IOException {
        if (!Files.isRegularFile(options.configFile())) {
            return options.config().snapshot(mapper);
        }
        JsonNode input = mapper.readTree(options.configFile().toFile());
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("启动配置须为 JSON 对象");
        }
        return (ObjectNode) input;
    }

    private ObjectNode describe(CaptureTargets configured) {
        CaptureTargets active = options.config().captureTargets();
        ObjectNode result =
                mapper.createObjectNode().put("configFile", options.configFile().toString());
        result.set("captureHosts", mapper.valueToTree(configured.hosts()));
        result.set("capturePathPrefixes", mapper.valueToTree(configured.pathPrefixes()));
        result.set("activeCaptureHosts", mapper.valueToTree(active.hosts()));
        result.set("activeCapturePathPrefixes", mapper.valueToTree(active.pathPrefixes()));
        result.put(
                "restartRequired",
                !active.hosts().equals(configured.hosts())
                        || !active.pathPrefixes().equals(configured.pathPrefixes()));
        return result;
    }
}
