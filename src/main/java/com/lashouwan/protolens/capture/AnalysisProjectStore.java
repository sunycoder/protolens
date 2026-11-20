package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 分析成果独立伴随记录落盘；版本校验防止多个页面静默覆盖批注，不存任意文件路径。 */
public final class AnalysisProjectStore {
    private static final int MAX_BYTES = 1024 * 1024;
    private final Path directory;
    private final ObjectMapper mapper;

    public AnalysisProjectStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    private Path path(String id) {
        if (!UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("记录 ID 无效");
        }
        return directory.resolve(id + ".research");
    }

    public synchronized ObjectNode read(String id) throws IOException {
        Path target = path(id);
        if (!Files.exists(target)) {
            ObjectNode empty = mapper.createObjectNode().put("revision", 0);
            empty.putArray("notes");
            empty.putArray("columns");
            empty.putArray("views");
            empty.putObject("widths");
            return empty;
        }
        if (Files.size(target) > MAX_BYTES) {
            throw new IOException("分析成果文件超过 1 MiB");
        }
        ObjectNode result = (ObjectNode) mapper.readTree(target.toFile());
        validate(result);
        return result;
    }

    /** 先完整校验再原子替换；失败保留原成果，revision 必须是调用方实际读取的版本。 */
    public synchronized ObjectNode save(String id, JsonNode input) throws IOException {
        validate(input);
        ObjectNode current = read(id);
        if (input.path("revision").asLong(-1) != current.path("revision").asLong()) {
            throw new IllegalArgumentException("分析成果已被其他页面更新，请重新加载后再保存；当前输入未覆盖");
        }
        ObjectNode next =
                mapper.createObjectNode().put("revision", current.path("revision").asLong() + 1);
        for (String field : List.of("notes", "columns", "views", "widths")) {
            next.set(field, input.path(field).deepCopy());
        }
        byte[] bytes = mapper.writeValueAsBytes(next);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("分析成果超过 1 MiB");
        }
        Path temporary = Files.createTempFile(directory, "research-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        path(id),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path(id), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return next;
    }

    public synchronized void delete(String id) throws IOException {
        Files.deleteIfExists(path(id));
    }

    public synchronized void copy(String source, String target, int from, int to)
            throws IOException {
        ObjectNode document = read(source);
        var notes = mapper.createArrayNode();
        for (JsonNode note : document.path("notes")) {
            int index = note.path("index").asInt();
            if (index >= from && index <= to) {
                notes.add(note.deepCopy());
            }
        }
        document.set("notes", notes);
        document.put("revision", 0);
        save(target, document);
    }

    /** 输入只接受有界的人工成果及显示配置；不接受消息体、脚本、HTML 或落盘目标。 */
    public void validate(JsonNode value) {
        if (!value.isObject()
                || !value.path("revision").canConvertToLong()
                || value.path("revision").asLong() < 0) {
            throw new IllegalArgumentException("分析成果版本无效");
        }
        if (!value.path("notes").isArray()
                || value.path("notes").size() > 200
                || !value.path("columns").isArray()
                || value.path("columns").size() > 6
                || !value.path("views").isArray()
                || value.path("views").size() > 12
                || !value.path("widths").isObject()
                || value.path("widths").size() > 30) {
            throw new IllegalArgumentException("批注、字段列、视图或列宽数量无效");
        }
        Set<String> kinds = Set.of("备注", "推测", "已验证");
        for (JsonNode note : value.path("notes")) {
            if (!note.path("index").canConvertToInt()
                    || note.path("index").asInt() < 0
                    || !text(note, "path", 512)
                    || !text(note, "text", 500)
                    || note.path("text").asText().isBlank()
                    || !kinds.contains(note.path("kind").asText())) {
                throw new IllegalArgumentException("批注内容或消息编号无效");
            }
        }
        for (JsonNode column : value.path("columns")) {
            if (!text(column, "path", 512)
                    || !column.path("path").asText().startsWith("/")
                    || column.path("path").asText().matches("(?s).*~(?![01]).*")
                    || !text(column, "label", 40)) {
                throw new IllegalArgumentException("字段列路径或名称无效");
            }
        }
        value.path("widths")
                .fields()
                .forEachRemaining(
                        entry -> {
                            if (entry.getKey().length() > 512
                                    || !entry.getValue().isNumber()
                                    || entry.getValue().asDouble() < 50
                                    || entry.getValue().asDouble() > 800) {
                                throw new IllegalArgumentException("列宽无效");
                            }
                        });
        for (JsonNode view : value.path("views")) {
            if (!text(view, "name", 100)
                    || !view.path("query").isObject()
                    || view.path("query").toString().length() > 8192) {
                throw new IllegalArgumentException("分析视图无效");
            }
        }
        if (value.toString().length() > MAX_BYTES) {
            throw new IllegalArgumentException("分析成果超过长度上限");
        }
    }

    private boolean text(JsonNode value, String key, int limit) {
        return value.path(key).isTextual() && value.path(key).asText().length() <= limit;
    }
}
