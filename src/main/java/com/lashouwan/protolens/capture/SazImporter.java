package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.DecodeStatus;
import com.lashouwan.protolens.enums.MessageDirection;
import com.lashouwan.protolens.protocol.GamePacketDecoder;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.WebSocketStreamDecoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 读取 Fiddler SAZ 的 raw/*_w.txt；长度分隔记录内保存的是原始 WebSocket wire 帧。 */
public final class SazImporter {
    private final ObjectMapper mapper;
    private final GamePacketDecoder decoder;

    public SazImporter(ProtocolCatalog catalog, ObjectMapper mapper) {
        this.mapper = mapper;
        this.decoder = new GamePacketDecoder(catalog, mapper);
    }

    /** 整体导入只在成功解析归档结构后返回；不落地或执行 HTTP 请求、Cookie 和归档路径。 */
    public ObjectNode read(byte[] archive) throws IOException {
        Map<String, byte[]> members = readMembers(archive);
        ObjectNode capture = mapper.createObjectNode();
        ArrayNode messages = capture.putArray("messages");
        ArrayNode connections = capture.putArray("connections");
        for (Map.Entry<String, byte[]> entry : members.entrySet()) {
            if (!entry.getKey().endsWith("_w.txt")) {
                continue;
            }
            String prefix = entry.getKey().substring(0, entry.getKey().length() - 6);
            String connection = prefix.substring(prefix.lastIndexOf('/') + 1);
            String request =
                    new String(
                            members.getOrDefault(prefix + "_c.txt", new byte[0]),
                            StandardCharsets.ISO_8859_1);
            String response =
                    new String(
                            members.getOrDefault(prefix + "_s.txt", new byte[0]),
                            StandardCharsets.ISO_8859_1);
            // 仅保存不带 query 的连接地址；握手 Cookie、签名及令牌不复制到分析元数据。
            String firstLine = request.split("[\r\n]", 2)[0];
            String[] requestParts = firstLine.split(" ");
            String endpoint =
                    requestParts.length > 1 ? requestParts[1].split("\\?", 2)[0] : "unknown";
            String extensions = "";
            for (String line : response.split("[\r\n]+")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-extensions:")) {
                    extensions =
                            line.substring(line.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
                }
            }
            ObjectNode metadata =
                    connections
                            .addObject()
                            .put("id", connection)
                            .put("endpoint", endpoint)
                            .put("extensions", extensions);
            int before = messages.size();
            try (WebSocketStreamDecoder client =
                            new WebSocketStreamDecoder(
                                    extensions.contains("client_no_context_takeover"));
                    WebSocketStreamDecoder server =
                            new WebSocketStreamDecoder(
                                    extensions.contains("server_no_context_takeover"))) {
                parseRecords(entry.getValue(), connection, messages, client, server);
            }
            metadata.put("messages", messages.size() - before);
        }
        if (connections.isEmpty()) {
            throw new IllegalArgumentException("SAZ 中未发现 raw/*_w.txt，请导出包含 WebSocket 消息的会话");
        }
        return capture;
    }

    private Map<String, byte[]> readMembers(byte[] archive) throws IOException {
        if (archive.length > AnalyzerLimits.MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("SAZ 文件超过 128 MiB");
        }
        Map<String, byte[]> members = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 20_000) {
                    throw new IllegalArgumentException("归档成员过多");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                boolean retain = name.matches("raw/[0-9]+_[wcs]\\.txt");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    size += read;
                    if (total > AnalyzerLimits.MAX_EXPANDED_BYTES
                            || size > AnalyzerLimits.MAX_ARCHIVE_BYTES) {
                        throw new IllegalArgumentException("归档解压后超过资源限制");
                    }
                    if (retain) {
                        output.write(buffer, 0, read);
                    }
                }
                if (retain && members.putIfAbsent(name, output.toByteArray()) != null) {
                    throw new IllegalArgumentException("归档包含重复成员 " + name);
                }
            }
        }
        return members;
    }

    private void parseRecords(
            byte[] data,
            String connection,
            ArrayNode messages,
            WebSocketStreamDecoder client,
            WebSocketStreamDecoder server) {
        int cursor = 0;
        int ordinal = 0;
        while (cursor < data.length) {
            while (cursor < data.length && (data[cursor] == '\r' || data[cursor] == '\n')) {
                cursor++;
            }
            if (cursor == data.length) {
                break;
            }
            int end = findHeaderEnd(data, cursor);
            if (end < 0 || end - cursor > 8192) {
                throw new IllegalArgumentException("WebSocket 记录头截断，偏移 " + cursor);
            }
            String headers = new String(data, cursor, end - cursor, StandardCharsets.ISO_8859_1);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("SAZ 记录头无效");
                }
                String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
                if (values.putIfAbsent(key, line.substring(colon + 1).trim()) != null) {
                    throw new IllegalArgumentException("重复 SAZ 记录头 " + key);
                }
            }
            boolean request = values.containsKey("request-length");
            if (request == values.containsKey("response-length")) {
                throw new IllegalArgumentException("记录方向缺失或冲突");
            }
            int length =
                    Integer.parseInt(values.get(request ? "request-length" : "response-length"));
            cursor = end + 4;
            if (length < 0
                    || length > AnalyzerLimits.MAX_MESSAGE_BYTES
                    || length > data.length - cursor) {
                throw new IllegalArgumentException("SAZ 帧长度无效或截断");
            }
            byte[] wire = Arrays.copyOfRange(data, cursor, cursor + length);
            cursor += length;
            ordinal++;
            String direction = request ? MessageDirection.CS.name() : MessageDirection.SC.name();
            ObjectNode message;
            try {
                byte[] packet = (request ? client : server).accept(wire);
                if (packet == null) {
                    continue;
                }
                message = decoder.decode(packet, direction);
            } catch (Exception failure) {
                message =
                        mapper.createObjectNode()
                                .put("direction", direction)
                                .put("status", DecodeStatus.FAILED.name())
                                .put("error", "WebSocket 解封装失败：" + failure.getMessage());
            }
            message.put("connection", connection);
            message.put("frameId", values.getOrDefault("id", Integer.toString(ordinal)));
            message.put("ordinal", ordinal);
            message.put("time", values.getOrDefault("doneread", ""));
            message.put("wireBytes", length);
            message.put("wireHex", HexFormat.of().formatHex(wire));
            message.put("compressed", wire.length > 0 && (wire[0] & 64) != 0);
            messages.add(message);
            if (messages.size() > AnalyzerLimits.MAX_MESSAGES) {
                throw new IllegalArgumentException("消息总数超过五万条");
            }
        }
    }

    private int findHeaderEnd(byte[] data, int start) {
        for (int index = start; index + 3 < data.length && index - start <= 8192; index++) {
            if (data[index] == '\r'
                    && data[index + 1] == '\n'
                    && data[index + 2] == '\r'
                    && data[index + 3] == '\n') {
                return index;
            }
        }
        return -1;
    }
}
