package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.CaptureState;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** 本地抓包仓库；单实例同步提交完整快照，数据落在独立 data 目录，不接入游戏数据库。 */
public final class CaptureStore {
    private final Path directory;
    private final ObjectMapper mapper;
    private final CaptureMessageQuery messageQuery;
    private final ProtocolCatalog catalog;

    /** 仅缓存最近访问的一份抓包；内容只在此同步仓库内读取，外部返回副本。 */
    private ObjectNode cachedCapture;

    /** 未保存的实时记录；包含采集中与已停止记录，保存或删除后释放。 */
    private final Map<String, ObjectNode> liveCaptures = new HashMap<>();

    private final Map<String, LiveMessageBuffer> liveBuffers = new HashMap<>();

    /** 新建连接使用当前设置；已有连接保留创建时预算。 */
    private int liveLimit = 10_000;

    private long liveByteLimit = 64L * 1024 * 1024;

    public synchronized void configureLive(int limit, long bytes) {
        if (limit < 100 || limit > 50_000 || bytes < 1024 * 1024 || bytes > 256L * 1024 * 1024) {
            throw new IllegalArgumentException("缓存条数需为 100～50000，字节预算需为 1～256 MiB");
        }
        liveLimit = limit;
        liveByteLimit = bytes;
    }

    public CaptureStore(Path directory, ObjectMapper mapper, ProtocolCatalog catalog)
            throws IOException {
        this.directory = directory;
        this.mapper = mapper;
        this.messageQuery = new CaptureMessageQuery(mapper);
        this.catalog = catalog;
        Files.createDirectories(directory);
    }

    /** 先在锁外完成解压和解码，发布文件时才持锁；无效归档不留下半份记录，也不长期阻塞实时追加。 */
    public ObjectNode importSaz(byte[] bytes, String filename) throws IOException {
        ProtocolCatalog snapshot = new ProtocolCatalog(catalog.snapshot());
        ObjectNode capture = new SazImporter(snapshot, mapper).read(bytes);
        capture.put("id", UUID.randomUUID().toString());
        capture.put("name", filename);
        capture.put("createdAt", Instant.now().toString());
        try {
            capture.put(
                    "sourceSha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", failure);
        }
        capture.put("schemaVersion", 1);
        capture.set("catalog", snapshot.summary(mapper));
        capture.set("dictionary", snapshot.snapshot());
        capture.putArray("marks");
        int index = 0;
        for (JsonNode node : capture.withArray("messages")) {
            ((ObjectNode) node).put("index", index++);
        }
        synchronized (this) {
            save(capture);
            return summary(capture);
        }
    }

    public synchronized ArrayNode list() throws IOException {
        List<ObjectNode> captures = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path :
                    files.filter(file -> file.getFileName().toString().endsWith(".json"))
                            .toList()) {
                try {
                    Path metadata =
                            directory.resolve(
                                    path.getFileName().toString().replace(".json", ".meta"));
                    String id = path.getFileName().toString().replace(".json", "");
                    if (liveCaptures.containsKey(id)) {
                        continue;
                    } else if (Files.exists(directory.resolve(id + ".journal"))) {
                        captures.add(summary(load(id)));
                    } else if (Files.isRegularFile(metadata)) {
                        ObjectNode meta = (ObjectNode) mapper.readTree(metadata.toFile());
                        if (meta.path("liveState").asText().equals(CaptureState.CAPTURING.name())) {
                            meta.put("liveState", CaptureState.INTERRUPTED.name());
                        }
                        captures.add(meta);
                    } else {
                        captures.add(
                                summary(load(path.getFileName().toString().replace(".json", ""))));
                    }
                } catch (RuntimeException | IOException failure) {
                    // 单个历史文件损坏不掩盖其他数据，界面明确显示该项读取失败。
                    captures.add(
                            mapper.createObjectNode()
                                    .put("name", path.getFileName().toString())
                                    .put("error", "历史文件无法读取：" + failure.getMessage()));
                }
            }
        }
        for (ObjectNode live : liveCaptures.values()) {
            captures.add(summary(live));
        }
        captures.sort(
                Comparator.comparing((ObjectNode node) -> node.path("createdAt").asText())
                        .reversed());
        ArrayNode result = mapper.createArrayNode();
        captures.forEach(result::add);
        return result;
    }

    public synchronized ObjectNode read(String id) throws IOException {
        Path path = path(id);
        if (!liveCaptures.containsKey(id) && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("抓包记录不存在");
        }
        return load(id).deepCopy();
    }

    public synchronized ObjectNode liveSummary(String id) {
        ObjectNode capture = liveCaptures.get(id);
        return capture == null ? mapper.createObjectNode() : summary(capture);
    }

    /** AI 和页面只读取轻量摘要，避免为了版本信息复制整份抓包。 */
    public synchronized ObjectNode describe(String id) throws IOException {
        return summary(load(id));
    }

    /** AI 先查看协议分布再缩小检索，不需要翻阅上万条摘要；分组数量有返回上限。 */
    public synchronized ObjectNode analysisOverview(String id) throws IOException {
        ObjectNode capture = load(id);
        ObjectNode result = summary(capture);
        Map<String, ObjectNode> groups = new HashMap<>();
        for (JsonNode message : capture.path("messages")) {
            String key =
                    message.path("direction").asText() + ":" + message.path("protocolId").asText();
            ObjectNode group = groups.get(key);
            if (group == null) {
                group =
                        mapper.createObjectNode()
                                .put("direction", message.path("direction").asText())
                                .put("protocolId", message.path("protocolId").asInt())
                                .put("protocolName", message.path("protocolName").asText())
                                .put("count", 0)
                                .put("firstIndex", message.path("index").asInt());
                groups.put(key, group);
            }
            group.put("count", group.path("count").asInt() + 1)
                    .put("lastIndex", message.path("index").asInt());
        }
        List<ObjectNode> distribution = new ArrayList<>(groups.values());
        distribution.sort(
                Comparator.comparingInt((ObjectNode group) -> group.path("count").asInt())
                        .reversed()
                        .thenComparingInt(group -> group.path("firstIndex").asInt()));
        ArrayNode items = result.putArray("protocolDistribution");
        for (int index = 0; index < Math.min(200, distribution.size()); index++) {
            items.add(distribution.get(index));
        }
        result.put("protocolGroupCount", groups.size())
                .put("distributionTruncated", groups.size() > 200);
        result.put("evidenceScope", "仅当前记录可见的消息；单连接不能证明全服或其他联盟的广播范围");
        return result;
    }

    /** 基于该抓包自己的字典查询，不能用当前全局字典解释旧包。 */
    public synchronized JsonNode searchProtocols(String id, String query) throws IOException {
        return new ProtocolCatalog(load(id).path("dictionary")).search(query, mapper);
    }

    public synchronized ObjectNode protocolDefinition(String id, String key) throws IOException {
        ObjectNode capture = load(id);
        ObjectNode result = mapper.createObjectNode().put("captureId", id).put("key", key);
        result.set("catalog", capture.path("catalog").deepCopy());
        result.set("message", capture.path("dictionary").path("messages").path(key).deepCopy());
        result.set("fields", capture.path("dictionary").path("definitions").path(key).deepCopy());
        return result;
    }

    /** 一次操作的区间按原编号冻结并发布新记录，源连接继续采集；已淘汰边界明确拒绝。 */
    public synchronized ObjectNode snapshotRange(String id, String name, int from, int to)
            throws IOException {
        ObjectNode source = load(id);
        JsonNode messages = source.path("messages");
        if (name.isBlank()
                || name.length() > 100
                || messages.isEmpty()
                || from < 0
                || to < from
                || from < messages.path(0).path("index").asInt()
                || to > messages.path(messages.size() - 1).path("index").asInt()) {
            throw new IllegalArgumentException("快照名称或区间无效，消息可能已淘汰；请刷新后重新选取仍保留的编号");
        }
        ObjectNode frozen = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals("messages")) {
                frozen.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        frozen.put("id", UUID.randomUUID().toString())
                .put("name", name.trim())
                .put("createdAt", Instant.now().toString())
                .put("temporary", false)
                .put("liveState", CaptureState.STOPPED.name())
                .put("analysisSnapshot", true);
        frozen.putObject("snapshotSource")
                .put("captureId", id)
                .put("from", from)
                .put("to", to)
                .put("sourceEvictedCount", source.path("evictedCount").asLong())
                .put("sourceClearedCount", source.path("clearedCount").asLong())
                .put("scope", "仅保存指定区间，不表示完整连接或证明广播范围");
        ArrayNode selected = frozen.putArray("messages");
        for (JsonNode message : messages) {
            int index = message.path("index").asInt();
            if (index >= from && index <= to) {
                selected.add(message.deepCopy());
            }
        }
        frozen.put("totalReceived", selected.size())
                .put("firstIndex", from)
                .put("lastIndex", to)
                .put("evictedCount", 0)
                .put("clearedCount", 0)
                .put("clearVersion", 0);
        frozen.putArray("marks")
                .addObject()
                .put("name", name.trim())
                .put("from", from)
                .put("to", to);
        for (JsonNode connection : frozen.withArray("connections")) {
            int count = 0;
            for (JsonNode message : selected) {
                if (message.path("connection").asText().equals(connection.path("id").asText())) {
                    count++;
                }
            }
            ((ObjectNode) connection).put("messages", count);
        }
        save(frozen);
        return summary(frozen);
    }

    private ObjectNode load(String id) throws IOException {
        if (liveCaptures.containsKey(id)) {
            ObjectNode capture = liveCaptures.get(id);
            capture.set("messages", liveBuffers.get(id).snapshot(mapper));
            return capture;
        }
        if (cachedCapture != null && cachedCapture.path("id").asText().equals(id)) {
            return cachedCapture;
        }
        cachedCapture = (ObjectNode) mapper.readTree(path(id).toFile());
        Path journal = directory.resolve(id + ".journal");
        if (Files.isRegularFile(journal)) {
            // 崩溃后仅回放完整日志行；末行写入中断不丢弃前面的已确认消息。
            try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode message = mapper.readTree(line);
                        if (message.path("index").asInt(-1)
                                == cachedCapture.withArray("messages").size()) {
                            cachedCapture.withArray("messages").add(message);
                        }
                    } catch (IOException failure) {
                        cachedCapture.put("recoveryWarning", "实时日志包含截断记录，已保留前序完整消息");
                        break;
                    }
                }
            }
            cachedCapture.put("liveState", CaptureState.INTERRUPTED.name());
        }
        if (cachedCapture.path("liveState").asText().equals(CaptureState.CAPTURING.name())) {
            cachedCapture.put("liveState", CaptureState.INTERRUPTED.name());
        }
        return cachedCapture;
    }

    /** 握手后仅创建内存记录；用户选择保存之前不产生抓包文件或增量日志。 */
    public synchronized String createLive(String connection, String endpoint, String extensions)
            throws IOException {
        return createLive(connection, endpoint, extensions, false);
    }

    public synchronized String createLive(
            String connection, String endpoint, String extensions, boolean continued)
            throws IOException {
        if (liveCaptures.size() >= AnalyzerLimits.MAX_LIVE_RECORDS) {
            throw new IllegalArgumentException("临时记录达到 32 份，请先保存或删除不用的记录");
        }
        Instant now = Instant.now();
        String name =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault())
                        .format(now);
        ObjectNode capture =
                mapper.createObjectNode()
                        .put("id", UUID.randomUUID().toString())
                        .put("name", name)
                        .put("createdAt", now.toString())
                        .put("schemaVersion", 1)
                        .put("liveState", CaptureState.CAPTURING.name())
                        .put("temporary", true);
        capture.put("startedMidConnection", continued);
        capture.set("catalog", catalog.summary(mapper));
        capture.set("dictionary", catalog.snapshot());
        capture.putArray("messages");
        capture.putArray("marks");
        capture.putArray("connections")
                .addObject()
                .put("id", connection)
                .put("endpoint", endpoint)
                .put("extensions", extensions)
                .put("messages", 0);
        String id = capture.path("id").asText();
        liveCaptures.put(id, capture);
        liveBuffers.put(id, new LiveMessageBuffer(liveLimit, liveByteLimit, mapper));
        return id;
    }

    /** 有界队列淘汰最旧消息，累计编号不重用；缓存变动不写磁盘。 */
    public synchronized void appendLive(String id, ObjectNode message) throws IOException {
        ObjectNode capture = liveCaptures.get(id);
        if (capture == null
                || !capture.path("liveState").asText().equals(CaptureState.CAPTURING.name())) {
            throw new IllegalArgumentException("实时记录已停止");
        }
        LiveMessageBuffer buffer = liveBuffers.get(id);
        buffer.append(message, mapper);
        buffer.describe(capture);
        // 查询可能物化了数组；追加后释放旧数组引用，淘汰对象才可被 GC 回收。
        capture.putArray("messages");
        ((ObjectNode) capture.withArray("connections").get(0))
                .put("messages", capture.path("totalReceived").asInt());
        int first = capture.path("firstIndex").asInt();
        for (JsonNode mark : capture.withArray("marks")) {
            ((ObjectNode) mark).put("expired", mark.path("from").asInt() < first);
        }
    }

    public synchronized void stopLive(String id, String state) {
        ObjectNode capture = liveCaptures.get(id);
        if (capture != null) {
            capture.put("liveState", state);
        }
    }

    /** 仅保存已停止的临时记录；快照发布成功之后才释放内存，失败仍可重试。 */
    public synchronized ObjectNode persistLive(String id, String name) throws IOException {
        ObjectNode capture = load(id).deepCopy();
        if (capture.path("liveState").asText().equals(CaptureState.CAPTURING.name())) {
            throw new IllegalArgumentException("请先停止采集再保存记录");
        }
        String title = name == null || name.isBlank() ? capture.path("name").asText() : name.trim();
        if (title.length() > 100) {
            throw new IllegalArgumentException("记录名称不能超过 100 字");
        }
        capture.put("name", title).put("temporary", false);
        save(capture);
        liveCaptures.remove(id);
        liveBuffers.remove(id);
        return summary(capture);
    }

    /** 活跃记录不能删除；关闭记录清除快照、旧日志和摘要，同时失效内存缓存。 */
    /** 清空本轮临时记录的消息和标记，保留记录 ID、字典、连接及名称，绝不修改历史文件。 */
    public synchronized void clearTemporary(Set<String> ids) {
        for (String id : ids) {
            ObjectNode capture = liveCaptures.get(id);
            if (capture == null) {
                continue;
            }
            LiveMessageBuffer buffer = liveBuffers.get(id);
            buffer.clear();
            buffer.describe(capture);
            capture.putArray("messages");
            capture.putArray("marks");
            capture.put("clearedAt", Instant.now().toString());
        }
    }

    public synchronized ObjectNode delete(String id) throws IOException {
        path(id);
        ObjectNode capture = load(id);
        if (capture.path("liveState").asText().equals(CaptureState.CAPTURING.name())) {
            throw new IllegalArgumentException("正在采集的记录不能删除，请先停止采集");
        }
        Files.deleteIfExists(directory.resolve(id + ".json"));
        Files.deleteIfExists(directory.resolve(id + ".journal"));
        Files.deleteIfExists(directory.resolve(id + ".meta"));
        liveCaptures.remove(id);
        liveBuffers.remove(id);
        if (cachedCapture != null && cachedCapture.path("id").asText().equals(id)) {
            cachedCapture = null;
        }
        return mapper.createObjectNode().put("deleted", id);
    }

    /** 列表只传输摘要，查询条件与筛选算法独立于持久化和实时缓存。 */
    public synchronized ObjectNode messages(String id, MessageQuery query) throws IOException {
        ObjectNode capture = load(id);
        return messageQuery.messages(capture, query, summary(capture));
    }

    public synchronized JsonNode detail(String id, int index) throws IOException {
        ObjectNode capture = load(id);
        JsonNode messages = capture.path("messages");
        int first = messages.isEmpty() ? 0 : messages.path(0).path("index").asInt();
        int position = index - first;
        if (position < 0
                || position >= messages.size()
                || messages.path(position).path("index").asInt() != index) {
            throw new IllegalArgumentException("消息不存在、已被清空或已被缓存淘汰，请刷新列表");
        }
        ObjectNode detail = (ObjectNode) messages.path(position).deepCopy();
        String protocolId = detail.path("protocolId").asText();
        detail.set(
                "schema",
                capture.path("dictionary").path("definitions").path(protocolId).deepCopy());
        detail.set(
                "definition",
                capture.path("dictionary").path("messages").path(protocolId).deepCopy());
        detail.set("catalog", capture.path("catalog").deepCopy());
        return detail;
    }

    /** 保存人工标注的操作区间；它只表示时间范围，不宣称区间内所有推送均由该操作触发。 */
    public synchronized ObjectNode mark(String id, String name, int from, int to)
            throws IOException {
        ObjectNode capture = read(id);
        if (name.isBlank()
                || name.length() > 100
                || from < 0
                || to < from
                || capture.path("messages").isEmpty()
                || from < capture.path("messages").path(0).path("index").asInt()
                || to
                        > capture.path("messages")
                                .path(capture.path("messages").size() - 1)
                                .path("index")
                                .asInt()) {
            throw new IllegalArgumentException("操作名或消息区间无效");
        }
        ObjectNode mark =
                mapper.createObjectNode().put("name", name).put("from", from).put("to", to);
        capture.withArray("marks").add(mark);
        if (liveCaptures.containsKey(id)) {
            liveCaptures.put(id, capture);
        } else {
            save(capture);
        }
        return summary(capture);
    }

    private ObjectNode summary(ObjectNode capture) {
        ObjectNode result = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = capture.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals("messages") && !field.getKey().equals("dictionary")) {
                result.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        if (liveBuffers.containsKey(capture.path("id").asText())) {
            liveBuffers.get(capture.path("id").asText()).describe(result);
            return result;
        }
        result.put("messageCount", capture.path("messages").size());
        if (!capture.path("messages").isEmpty()) {
            result.put("firstIndex", capture.path("messages").path(0).path("index").asInt());
            result.put(
                    "lastIndex",
                    capture.path("messages")
                            .path(capture.path("messages").size() - 1)
                            .path("index")
                            .asInt());
        }
        ObjectNode counts = result.putObject("counts");
        for (JsonNode message : capture.path("messages")) {
            String status = message.path("status").asText();
            counts.put(status, counts.path(status).asInt() + 1);
        }
        return result;
    }

    private Path path(String id) {
        if (!UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("记录 ID 无效");
        }
        return directory.resolve(id + ".json");
    }

    private void save(ObjectNode capture) throws IOException {
        Path target = path(capture.path("id").asText());
        Path temporary = Files.createTempFile(directory, "capture-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), capture);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedCapture = capture;
            // 摘要是可重建缓存；写入失败不把已成功发布的完整抓包误报为失败。
            Path metadata = directory.resolve(capture.path("id").asText() + ".meta");
            Path metadataTemporary = Files.createTempFile(directory, "metadata-", ".tmp");
            try {
                mapper.writeValue(metadataTemporary.toFile(), summary(capture));
                Files.move(metadataTemporary, metadata, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                Files.deleteIfExists(metadata);
            } finally {
                Files.deleteIfExists(metadataTemporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
