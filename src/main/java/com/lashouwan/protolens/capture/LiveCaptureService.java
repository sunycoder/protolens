package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.CaptureEventType;
import com.lashouwan.protolens.enums.CaptureState;
import com.lashouwan.protolens.enums.DecodeStatus;
import com.lashouwan.protolens.enums.MessageDirection;
import com.lashouwan.protolens.enums.PacketInputFormat;
import com.lashouwan.protolens.protocol.GamePacketDecoder;
import com.lashouwan.protolens.protocol.ProtocolCatalog;
import com.lashouwan.protolens.protocol.ProtocolCatalogService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 实时采集状态所有者；单锁串行推进来源顺序、每连接解压上下文和缓存，HTTP 仅负责适配。 */
public final class LiveCaptureService implements AutoCloseable {
    private final CaptureStore captures;
    private final ObjectMapper mapper;
    private final GamePacketDecoder decoder;
    private final Consumer<ObjectNode> changed;

    /** key 为采集器提供的连接 ID，value 仅属于当前来源，不跨采集进程复用。 */
    private final Map<String, LiveConnection> connections = new LinkedHashMap<>();

    /** key 为传输事件序号，value 为内容摘要；有界窗口用于结果不明的批次重试。 */
    private final Map<Long, String> accepted = new LinkedHashMap<>();

    /** 当前采集轮次创建的记录 ID，包含已断开的连接；停止保存仅处理这批记录。 */
    private final Set<String> sessionCaptures = new LinkedHashSet<>();

    /** 本轮仍未保存的全部记录，连接关闭或停止后也保留，供清空会话使用。 */
    private final Set<String> currentRoundCaptures = new LinkedHashSet<>();

    private String source = "";
    private String generation = "";
    private long acknowledged;
    private long received;
    private long duplicates;
    private long missing;
    private long version;
    private String lastEventAt = "";
    private String lastError = "";
    private boolean enabled;

    public LiveCaptureService(
            CaptureStore captures,
            ProtocolCatalog catalog,
            ObjectMapper mapper,
            Consumer<ObjectNode> changed) {
        this.captures = captures;
        this.mapper = mapper;
        this.changed = changed;
        this.decoder = new GamePacketDecoder(catalog, mapper);
    }

    /** 新轮次必须重新握手；幂等重复开始不清空正在使用的状态。 */
    public synchronized ObjectNode start() {
        if (enabled) {
            return status();
        }
        sessionCaptures.clear();
        currentRoundCaptures.clear();
        lastError = "";
        received = 0;
        duplicates = 0;
        missing = 0;
        generation = UUID.randomUUID().toString();
        enabled = true;
        version++;
        publish();
        return status();
    }

    public synchronized ObjectNode stop() throws IOException {
        return stop(false, "");
    }

    /** 停止默认只冻结临时缓存；显式保存失败时记录仍可在页面逐份重试，不伪报保存成功。 */
    public synchronized ObjectNode stop(boolean save, String name) throws IOException {
        if (name == null || name.length() > 100) {
            throw new IllegalArgumentException("记录名称不能超过 100 字");
        }
        enabled = false;
        try {
            finishConnections(CaptureState.STOPPED);
            if (save) {
                for (String id : sessionCaptures) {
                    captures.persistLive(id, name);
                    currentRoundCaptures.remove(id);
                }
            }
            sessionCaptures.clear();
        } finally {
            source = "";
            acknowledged = 0;
            accepted.clear();
            version++;
            publish();
        }
        return status();
    }

    public synchronized ObjectNode status() {
        ObjectNode result =
                mapper.createObjectNode()
                        .put("enabled", enabled)
                        .put("sessionRecordCount", currentRoundCaptures.size())
                        .put("source", source)
                        .put("generation", generation)
                        .put("acknowledged", acknowledged)
                        .put("receivedFrames", received)
                        .put("duplicates", duplicates)
                        .put("missingEvents", missing)
                        .put("lastEventAt", lastEventAt)
                        .put("lastError", lastError)
                        .put("version", version);
        int sessionMessages = 0;
        for (String id : currentRoundCaptures) {
            ObjectNode summary = captures.liveSummary(id);
            if (summary.path("temporary").asBoolean()) {
                sessionMessages += summary.path("messageCount").asInt();
            }
        }
        result.put("sessionMessageCount", sessionMessages);
        ArrayNode active = result.putArray("connections");
        for (Map.Entry<String, LiveConnection> entry : connections.entrySet()) {
            LiveConnection connection = entry.getValue();
            if (connection.captureId.isBlank()) {
                continue;
            }
            ObjectNode retention = captures.liveSummary(connection.captureId);
            active.addObject()
                    .put("connection", entry.getKey())
                    .put("captureId", connection.captureId)
                    .put("frames", connection.frames)
                    .put("messages", connection.messages)
                    .put("failed", connection.failed)
                    .put("retainedMessages", retention.path("messageCount").asInt())
                    .put("clearVersion", retention.path("clearVersion").asLong())
                    .put("clearedCount", retention.path("clearedCount").asLong())
                    .put("evictedCount", retention.path("evictedCount").asLong())
                    .put("endpoint", connection.endpoint)
                    .put("error", connection.error);
        }
        return result;
    }

    /** 先验证完整批次形状；每条事件处理成功后才确认序号，重试不得再次推进 inflater。 */
    public synchronized ObjectNode ingest(JsonNode batch) throws IOException {
        if (!enabled) {
            throw new IllegalArgumentException("工作台尚未开始实时采集");
        }
        String incomingSource = batch.path("source").asText();
        UUID.fromString(incomingSource);
        JsonNode events = batch.path("events");
        if (!events.isArray() || events.size() > AnalyzerLimits.MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("每批最多 128 个事件");
        }
        for (JsonNode event : events) {
            validate(event);
        }
        if (!source.equals(incomingSource)) {
            if (!events.isEmpty() && events.path(0).path("sequence").asLong() != 1) {
                throw new IllegalArgumentException("新采集源必须从 sequence=1 开始；请重启采集器并重进小游戏");
            }
            finishConnections(CaptureState.INTERRUPTED);
            source = incomingSource;
            acknowledged = 0;
            accepted.clear();
        }
        for (JsonNode event : events) {
            long sequence = event.path("sequence").asLong();
            String digest = digest(event.toString());
            if (sequence <= acknowledged) {
                if (!digest.equals(accepted.get(sequence))) {
                    throw new IllegalArgumentException("重试内容不同或超出去重窗口");
                }
                duplicates++;
                continue;
            }
            if (sequence != acknowledged + 1) {
                missing++;
                lastError = "采集事件缺号：期望 " + (acknowledged + 1) + "，收到 " + sequence;
                version++;
                publish();
                throw new IllegalArgumentException(lastError);
            }
            try {
                apply(event);
            } catch (IOException | RuntimeException failure) {
                lastError = failure.getMessage();
                version++;
                publish();
                throw failure;
            }
            acknowledged = sequence;
            accepted.put(sequence, digest);
            if (accepted.size() > AnalyzerLimits.RETRY_WINDOW_EVENTS) {
                accepted.remove(accepted.keySet().iterator().next());
            }
        }
        lastEventAt = Instant.now().toString();
        version++;
        publish();
        return status();
    }

    private void validate(JsonNode event) {
        if (!event.path("sequence").canConvertToLong() || event.path("sequence").asLong() < 1) {
            throw new IllegalArgumentException("sequence 必须是正整数");
        }
        CaptureEventType type = CaptureEventType.fromCode(event.path("type").asText());
        if (type == CaptureEventType.OPEN
                || type == CaptureEventType.FRAME
                || type == CaptureEventType.PACKET
                || type == CaptureEventType.CLOSE) {
            if (!event.path("connection").asText().matches("[a-zA-Z0-9-]{1,64}")) {
                throw new IllegalArgumentException("连接 ID 无效");
            }
        }
        if (type == CaptureEventType.OPEN) {
            String endpoint = event.path("endpoint").asText();
            if (endpoint.length() > 2048
                    || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
                throw new IllegalArgumentException("连接地址无效");
            }
            if (event.path("extensions").asText().length() > 1024) {
                throw new IllegalArgumentException("扩展头过长");
            }
        }
        if (type == CaptureEventType.FRAME || type == CaptureEventType.PACKET) {
            MessageDirection.valueOf(event.path("direction").asText());
            String raw =
                    event.path(type == CaptureEventType.FRAME ? "wireBase64" : "packetBase64")
                            .asText();
            if (raw.length() > 2 * AnalyzerLimits.MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("帧过大");
            }
            byte[] bytes = Base64.getDecoder().decode(raw);
            if (bytes.length < 2 || bytes.length > AnalyzerLimits.MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("帧长度无效");
            }
            if (event.path("time").asText().length() > 100) {
                throw new IllegalArgumentException("时间格式过长");
            }
        }
        if (type == CaptureEventType.ERROR && event.path("message").asText().length() > 2048) {
            throw new IllegalArgumentException("诊断过长");
        }
    }

    private void apply(JsonNode event) throws IOException {
        CaptureEventType type = CaptureEventType.fromCode(event.path("type").asText());
        String id = event.path("connection").asText();
        if (type == CaptureEventType.OPEN) {
            if (connections.containsKey(id)) {
                throw new IllegalArgumentException("重复握手事件");
            }
            if (connections.size() >= AnalyzerLimits.MAX_LIVE_RECORDS) {
                throw new IllegalArgumentException("活跃连接超过 32 条，请停止后重新开始");
            }
            String endpoint = event.path("endpoint").asText().split("\\?", 2)[0];
            String extensions = event.path("extensions").asText().toLowerCase(Locale.ROOT);
            String captureId = captures.createLive(id, endpoint, extensions);
            sessionCaptures.add(captureId);
            currentRoundCaptures.add(captureId);
            connections.put(id, new LiveConnection(captureId, endpoint, extensions));
        } else if (type == CaptureEventType.FRAME || type == CaptureEventType.PACKET) {
            appendMessage(event, id, type == CaptureEventType.PACKET);
        } else if (type == CaptureEventType.CLOSE) {
            LiveConnection connection = connections.remove(id);
            if (connection != null) {
                captures.stopLive(connection.captureId, CaptureState.CLOSED.name());
                connection.close();
            }
        } else if (type == CaptureEventType.ERROR) {
            lastError = event.path("message").asText();
        }
    }

    /** 一条消息完成解封装、解码和缓存后才增加连接计数；失败上下文保留原数据并明确标记。 */
    private void appendMessage(JsonNode event, String id, boolean completePacket)
            throws IOException {
        received++;
        LiveConnection connection = connections.get(id);
        if (connection == null) {
            lastError = "收到未捕获握手的连接 " + id + "，请在开始采集后重新进入小游戏";
            return;
        }
        byte[] frame =
                Base64.getDecoder()
                        .decode(
                                event.path(completePacket ? "packetBase64" : "wireBase64")
                                        .asText());
        String direction = event.path("direction").asText();
        connection.frames++;
        ObjectNode message;
        try {
            if (!connection.error.isBlank()) {
                throw new IllegalArgumentException(connection.error);
            }
            byte[] packet =
                    completePacket
                            ? frame
                            : (MessageDirection.CS.name().equals(direction)
                                            ? connection.client
                                            : connection.server)
                                    .accept(frame);
            if (packet == null) {
                return;
            }
            message = decoder.decode(packet, direction);
        } catch (Exception failure) {
            connection.error = "连接上下文不可继续：" + failure.getMessage();
            message =
                    mapper.createObjectNode()
                            .put("direction", direction)
                            .put("status", DecodeStatus.FAILED.name())
                            .put("error", connection.error);
        }
        message.put("connection", id)
                .put("frameId", event.path("frameId").asText())
                .put("ordinal", connection.frames)
                .put("time", event.path("time").asText())
                .put("wireBytes", completePacket ? 0 : frame.length)
                .put("wireHex", completePacket ? "" : HexFormat.of().formatHex(frame))
                .put("compressed", !completePacket && (frame[0] & 64) != 0)
                .put("transportSequence", event.path("sequence").asLong())
                .put(
                        "inputFormat",
                        (completePacket
                                        ? PacketInputFormat.DECOMPRESSED_MESSAGE
                                        : PacketInputFormat.WEBSOCKET_WIRE_FRAME)
                                .name());
        try {
            captures.appendLive(connection.captureId, message);
        } catch (IOException | RuntimeException failure) {
            connection.error = "本地缓存写入失败，请停止采集：" + failure.getMessage();
            lastError = connection.error;
            enabled = false;
            throw failure;
        }
        connection.messages++;
        if (DecodeStatus.FAILED.name().equals(message.path("status").asText())) {
            connection.failed++;
        }
    }

    /** 与 ingest 共用锁，换版时不能与单包解码交错。 */
    public synchronized ObjectNode reloadCatalog(
            ProtocolCatalogService protocols, String path, String file) throws Exception {
        if (enabled) {
            throw new IllegalArgumentException("请先停止采集，再加载竞品协议；随后重新进入游戏");
        }
        return protocols.reload(path, file);
    }

    public synchronized ObjectNode configure(AnalyzerSettingsStore settings, JsonNode input)
            throws IOException {
        if (enabled) {
            throw new IllegalArgumentException("请先停止采集再修改缓存上限");
        }
        ObjectNode result = settings.update(input);
        captures.configureLive(
                result.path("maxMessages").asInt(),
                result.path("maxCacheMiB").asLong() * AnalyzerLimits.BYTES_PER_MIB);
        return result;
    }

    public synchronized ObjectNode deleteCapture(String id) throws IOException {
        ObjectNode result = captures.delete(id);
        sessionCaptures.remove(id);
        currentRoundCaptures.remove(id);
        return result;
    }

    public synchronized ObjectNode saveCapture(String id, String name) throws IOException {
        ObjectNode result = captures.persistLive(id, name);
        sessionCaptures.remove(id);
        currentRoundCaptures.remove(id);
        return result;
    }

    /** 清空本次采集的消息但保留每条记录与连接；不重置压缩字典、传输去重或原始编号。 */
    public synchronized ObjectNode clearSession() {
        Set<String> ids = new LinkedHashSet<>(currentRoundCaptures);
        Set<String> cleared = captures.clearTemporary(ids);
        received = 0;
        version++;
        publish();
        ObjectNode result = status();
        result.set("clearedCaptureIds", mapper.valueToTree(cleared));
        return result;
    }

    private void finishConnections(CaptureState state) {
        for (LiveConnection connection : connections.values()) {
            captures.stopLive(connection.captureId, state.name());
            connection.close();
        }
        connections.clear();
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", failure);
        }
    }

    private void publish() {
        changed.accept(status());
    }

    @Override
    public synchronized void close() throws IOException {
        stop();
    }
}
