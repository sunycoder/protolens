package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureStore;
import com.lashouwan.protolens.enums.ProtocolSearchScope;
import com.lashouwan.protolens.mcp.ClientSourceReader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 协议查询的字典选择、记录关联和源码验证；不改变采集、当前字典或历史记录内容。 */
public final class ProtocolQueryService {
    private final ProtocolCatalog catalog;
    private final CaptureStore captures;
    private final ClientSourceReader sources;
    private final ObjectMapper mapper;

    /** 记录字典创建后不再换版；按访问顺序只缓存最近四份索引。 */
    private final Map<String, ProtocolQueryIndex> recordIndexes =
            new LinkedHashMap<>(8, 0.75f, true);

    public ProtocolQueryService(
            ProtocolCatalog catalog,
            CaptureStore captures,
            ClientSourceReader sources,
            ObjectMapper mapper) {
        this.catalog = catalog;
        this.captures = captures;
        this.sources = sources;
        this.mapper = mapper;
    }

    private ProtocolQueryIndex index(String captureId) throws IOException {
        if (captureId.isEmpty()) {
            return catalog.queryIndex(mapper);
        }
        // 即使已缓存也确认记录仍存在，删除后的目录请求不能使用缓存绕过记录生命周期。
        captures.describe(captureId);
        synchronized (recordIndexes) {
            ProtocolQueryIndex cached = recordIndexes.get(captureId);
            if (cached != null) {
                return cached;
            }
        }
        ProtocolQueryIndex built =
                new ProtocolCatalog(captures.protocolDictionary(captureId)).queryIndex(mapper);
        synchronized (recordIndexes) {
            recordIndexes.put(captureId, built);
            if (recordIndexes.size() > ProtocolQueryLimits.MAX_CACHED_CAPTURE_DICTIONARIES) {
                recordIndexes.remove(recordIndexes.keySet().iterator().next());
            }
        }
        return built;
    }

    public ObjectNode search(
            String captureId, String query, String scope, String direction, int offset, int limit)
            throws IOException {
        return index(captureId)
                .search(query, ProtocolSearchScope.fromCode(scope), direction, offset, limit)
                .put("captureId", captureId);
    }

    public ObjectNode definition(
            String captureId,
            String key,
            String version,
            int fieldOffset,
            String matchPath,
            String contextCaptureId)
            throws IOException {
        ProtocolQueryIndex index = index(captureId);
        index.requireVersion(version);
        ObjectNode result =
                index.definition(key, fieldOffset, matchPath).put("captureId", captureId);
        String linkedCaptureId = captureId.isEmpty() ? contextCaptureId : captureId;
        if (!linkedCaptureId.isEmpty() && result.has("protocolId")) {
            // 当前字典与记录必须完整指纹一致，相同 ID 或源码文件名不足以证明版本兼容。
            if (index.version().equals(index(linkedCaptureId).version())) {
                result.put("linkedCaptureId", linkedCaptureId);
                result.set("occurrences", captures.protocolOccurrences(linkedCaptureId, key));
            } else {
                result.put("linkNotice", "当前记录与所选字典版本不同，请切换当前记录字典查看对应定义");
            }
        }
        return result;
    }

    public ObjectNode source(String captureId, String key, String version) throws Exception {
        ProtocolQueryIndex index = index(captureId);
        index.requireVersion(version);
        int line = index.definition(key, 0).path("sourceLine").asInt();
        return sources.protocolSource(index.catalog(), line);
    }
}
