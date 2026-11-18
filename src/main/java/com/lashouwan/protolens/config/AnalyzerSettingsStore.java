package com.lashouwan.protolens.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** 只保存页面选定的协议来源与缓存预算；端口和启动开关始终来自 AnalyzerConfig。 */
public final class AnalyzerSettingsStore {
    private static final Set<String> PREFERENCE_FIELDS =
            Set.of(
                    "competitorPath",
                    "protocolFile",
                    "loadedProtocolFile",
                    "maxMessages",
                    "maxCacheMiB");
    private final Path file;
    private final ObjectMapper mapper;

    /** 配置基础值和本机偏好的合并快照；本类锁内更新，对外返回副本。 */
    private ObjectNode settings;

    public AnalyzerSettingsStore(AnalyzerLaunchOptions options, ObjectMapper mapper)
            throws IOException {
        this.mapper = mapper;
        this.file = options.dataDirectory().resolve("settings.json");
        this.settings = options.config().snapshot(mapper).put("loadedProtocolFile", "");
        if (Files.isRegularFile(file)) {
            JsonNode input = mapper.readTree(file.toFile());
            if (!input.isObject()) {
                throw new IllegalArgumentException("本机 settings.json 须为 JSON 对象");
            }
            for (String key : PREFERENCE_FIELDS) {
                if (input.has(key)) {
                    settings.set(key, input.get(key));
                }
            }
        }
        validate(settings);
    }

    public synchronized ObjectNode snapshot() {
        return settings.deepCopy();
    }

    /** 完整提取成功后记住用户来源，文件移动或失败时不隐式换成另一个版本。 */
    public synchronized void saveProtocolSource(
            String source, String relativeFile, String loadedFile) throws IOException {
        ObjectNode next =
                settings.deepCopy()
                        .put("competitorPath", source)
                        .put("protocolFile", relativeFile)
                        .put("loadedProtocolFile", loadedFile);
        save(next);
    }

    /** 只接受页面可修改的预算，保存前完成类型与范围校验；失败保留旧快照。 */
    public synchronized ObjectNode update(JsonNode input) throws IOException {
        ObjectNode next = settings.deepCopy();
        for (String key : new String[] {"maxMessages", "maxCacheMiB"}) {
            if (input.has(key)) {
                next.set(key, input.get(key));
            }
        }
        validate(next);
        save(next);
        return snapshot();
    }

    private void save(ObjectNode next) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            ObjectNode preferences = mapper.createObjectNode();
            for (String key : PREFERENCE_FIELDS) {
                preferences.set(key, next.get(key));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), preferences);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            settings = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validate(JsonNode input) {
        for (String key : new String[] {"competitorPath", "protocolFile", "loadedProtocolFile"}) {
            if (!input.path(key).isTextual()) {
                throw new IllegalArgumentException(key + " 必须为字符串");
            }
        }
        JsonNode count = input.path("maxMessages");
        JsonNode bytes = input.path("maxCacheMiB");
        if (!count.isIntegralNumber()
                || !count.canConvertToInt()
                || count.asInt() < 100
                || count.asInt() > 50000
                || !bytes.isIntegralNumber()
                || !bytes.canConvertToInt()
                || bytes.asInt() < 1
                || bytes.asInt() > 256) {
            throw new IllegalArgumentException("maxMessages 需为 100～50000，maxCacheMiB 需为 1～256 的整数");
        }
    }
}
