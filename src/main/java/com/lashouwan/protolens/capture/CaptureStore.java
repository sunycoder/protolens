package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.CaptureState;
import com.lashouwan.protolens.enums.ConnectionIdentityState;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.LinkedHashSet;
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
    private int liveLimit = AnalyzerLimits.DEFAULT_CACHE_MESSAGES;

    private long liveByteLimit = AnalyzerLimits.DEFAULT_CACHE_MIB * AnalyzerLimits.BYTES_PER_MIB;

    public synchronized void configureLive(int limit, long bytes) {
        if (limit < AnalyzerLimits.MIN_CACHE_MESSAGES
                || limit > AnalyzerLimits.MAX_CACHE_MESSAGES
                || bytes < AnalyzerLimits.MIN_CACHE_MIB * AnalyzerLimits.BYTES_PER_MIB
                || bytes > AnalyzerLimits.MAX_CACHE_MIB * AnalyzerLimits.BYTES_PER_MIB) {
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
            ObjectNode identity =
                    ConnectionIdentity.observe(node, capture.path("dictionary"), mapper);
            if (identity != null) {
                updateIdentity(capture, identity, node.path("connection").asText());
            }
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
                    } else if (Files.exists(directory.resolve(id + ".journal"))
                            || Files.exists(directory.resolve(id + ".connections"))) {
                        captures.add(summary(load(id)));
                    } else if (Files.isRegularFile(metadata)) {
                        ObjectNode meta = (ObjectNode) mapper.readTree(metadata.toFile());
                        if (CaptureState.CAPTURING.name().equals(meta.path("liveState").asText())) {
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
    public ObjectNode analysisOverview(String id) throws IOException {
        ObjectNode capture;
        ObjectNode result;
        synchronized (this) {
            capture = queryView(id);
            result = summary(capture);
        }
        Map<String, ObjectNode> groups = new HashMap<>();
        for (JsonNode message : capture.path("messages")) {
            String key =
                    message.path("direction").asText()
                            + ":"
                            + (message.hasNonNull("protocolId")
                                    ? "id:" + message.path("protocolId").asText()
                                    : "name:" + message.path("protocolName").asText());
            ObjectNode group = groups.get(key);
            if (group == null) {
                group =
                        mapper.createObjectNode()
                                .put("direction", message.path("direction").asText())
                                .put("protocolName", message.path("protocolName").asText())
                                .put("count", 0)
                                .put("firstIndex", message.path("index").asInt());
                groups.put(key, group);
                if (message.hasNonNull("protocolId")) {
                    group.set("protocolId", message.path("protocolId"));
                }
            }
            group.put("count", group.path("count").asInt() + 1)
                    .put("lastIndex", message.path("index").asInt())
                    .put("lastTime", message.path("time").asText());
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
                .put(
                        "distributionTruncated",
                        groups.size() > AnalyzerLimits.MAX_PROTOCOL_OVERVIEW_GROUPS);
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

    /** 查询目录使用记录创建时的字典；复制后在仓库锁外建立索引，不阻塞后续实时消息追加。 */
    public synchronized JsonNode protocolDictionary(String id) throws IOException {
        return load(id).path("dictionary").deepCopy();
    }

    /** 当前记录保留窗口的协议统计；按真实方向分别计数，不应用页面临时筛选。 */
    public synchronized ObjectNode protocolOccurrences(String id, String key) throws IOException {
        ObjectNode capture = load(id);
        int count = 0;
        int first = -1;
        int last = -1;
        ObjectNode directions = mapper.createObjectNode();
        for (JsonNode message : capture.path("messages")) {
            if (!key.equals(message.path("protocolId").asText())) {
                continue;
            }
            count++;
            int index = message.path("index").asInt();
            if (first < 0) {
                first = index;
            }
            last = index;
            String direction = message.path("direction").asText("UNKNOWN");
            directions.put(direction, directions.path(direction).asInt() + 1);
        }
        ObjectNode result =
                mapper.createObjectNode()
                        .put("count", count)
                        .put("firstIndex", first)
                        .put("lastIndex", last)
                        .put("scope", "本记录当前保留窗口；不应用消息列表筛选，已淘汰消息不计入");
        result.set("directions", directions);
        return result;
    }

    /** 一次操作的区间按原编号冻结并发布新记录，源连接继续采集；已淘汰边界明确拒绝。 */
    public ObjectNode snapshotRange(String id, String name, int from, int to) throws IOException {
        return snapshotRange(id, name, from, to, "");
    }

    /** 先冻结区间再在锁外发布新文件；磁盘失败不改变源记录，调用方可保留输入重试。 */
    public ObjectNode snapshotRange(String id, String name, int from, int to, String context)
            throws IOException {
        ObjectNode frozen;
        synchronized (this) {
            frozen = freezeRange(id, name, from, to, context);
        }
        save(frozen);
        synchronized (this) {
            return summary(frozen);
        }
    }

    /** 截止当前的边界在同一把锁内确定，不受浏览器旧计数或源连接追加、淘汰影响。 */
    public ObjectNode snapshotCurrent(String id, String name) throws IOException {
        ObjectNode frozen;
        synchronized (this) {
            ObjectNode source = load(id);
            JsonNode messages = source.path("messages");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("当前没有可保存的消息");
            }
            frozen =
                    freezeRange(
                            id,
                            name,
                            messages.path(0).path("index").asInt(),
                            messages.path(messages.size() - 1).path("index").asInt(),
                            "");
        }
        save(frozen);
        synchronized (this) {
            return summary(frozen);
        }
    }

    /** 仅由持锁入口调用；保留原编号、字典和来源，背景说明不超过 2000 字。 */
    private ObjectNode freezeRange(String id, String name, int from, int to, String context)
            throws IOException {
        ObjectNode source = load(id);
        JsonNode messages = source.path("messages");
        if (context.length() > AnalyzerLimits.MAX_OPERATION_CONTEXT_LENGTH
                || name.isBlank()
                || name.length() > AnalyzerLimits.MAX_CAPTURE_NAME_LENGTH
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
            if (!"messages".equals(field.getKey())) {
                frozen.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        frozen.put("id", UUID.randomUUID().toString())
                .put("name", name.trim())
                .put("createdAt", Instant.now().toString())
                .put("temporary", false)
                .put("liveState", CaptureState.STOPPED.name())
                .put("analysisSnapshot", true);
        frozen.put("operationContext", context.trim());
        frozen.put("continuous", false);
        frozen.remove(List.of("diskFirstIndex", "diskLastIndex", "continuousError"));
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
            ObjectNode frozenConnection = (ObjectNode) connection;
            JsonNode identity = identityAt(connection, to);
            JsonNode latest = connection.path("identity");
            if (identity == null ? !latest.isMissingNode() : !identity.equals(latest)) {
                frozenConnection.put("note", "");
            }
            if (identity == null) {
                frozenConnection.remove("identity");
            } else {
                frozenConnection.set("identity", identity.deepCopy());
            }
            ArrayNode history = mapper.createArrayNode();
            for (JsonNode event : connection.path("identityHistory")) {
                if (event.path("sourceIndex").asInt() <= to) {
                    history.add(event.deepCopy());
                }
            }
            frozenConnection.set("identityHistory", history);
            frozenConnection.put("messages", count);
        }
        return frozen;
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
        cachedCapture = readArchive(id);
        return cachedCapture;
    }

    /** 回放已保存基线及逐条追加日志；按原始编号衔接，尾部截断不掩盖已保存证据。 */
    private ObjectNode readArchive(String id) throws IOException {
        ObjectNode restored = (ObjectNode) mapper.readTree(path(id).toFile());
        Path journal = directory.resolve(id + ".journal");
        if (Files.isRegularFile(journal)) {
            // 崩溃后仅回放完整日志行；末行写入中断不丢弃前面的已确认消息。
            try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode message = mapper.readTree(line);
                        ArrayNode messages = restored.withArray("messages");
                        int expected =
                                messages.isEmpty()
                                        ? restored.path("firstIndex").asInt()
                                        : messages.path(messages.size() - 1).path("index").asInt()
                                                + 1;
                        int index = message.path("index").asInt(-1);
                        if (index == expected) {
                            messages.add(message);
                            ObjectNode identity =
                                    ConnectionIdentity.observe(
                                            message, restored.path("dictionary"), mapper);
                            if (identity != null) {
                                updateIdentity(
                                        restored, identity, message.path("connection").asText());
                            }
                        } else if (index > expected || index < 0) {
                            restored.put("recoveryWarning", "实时日志编号中断，已保留前序完整消息");
                            break;
                        }
                    } catch (IOException failure) {
                        restored.put("recoveryWarning", "实时日志包含截断记录，已保留前序完整消息");
                        break;
                    }
                }
            }
            restored.put("liveState", CaptureState.INTERRUPTED.name());
        }
        if (CaptureState.CAPTURING.name().equals(restored.path("liveState").asText())) {
            restored.put("liveState", CaptureState.INTERRUPTED.name());
        }
        restored.put("continuous", false).put("temporary", false);
        if (restored.has("diskFirstIndex") && !restored.path("messages").isEmpty()) {
            int last =
                    restored.path("messages")
                            .path(restored.path("messages").size() - 1)
                            .path("index")
                            .asInt();
            restored.put("diskLastIndex", last).put("totalReceived", last + 1);
        }
        Path annotations = directory.resolve(id + ".connections");
        if (Files.isRegularFile(annotations)) {
            JsonNode savedConnections = mapper.readTree(annotations.toFile());
            for (JsonNode connection : restored.withArray("connections")) {
                for (JsonNode saved : savedConnections) {
                    // 只在备注对应的身份证据仍相同时恢复，后续登录不得继承旧账号备注。
                    if (connection.path("id").equals(saved.path("id"))
                            && connection.path("identity").equals(saved.path("identity"))) {
                        ((ObjectNode) connection).put("note", saved.path("note").asText());
                    }
                }
            }
        }
        restored.setAll(summary(restored, false));
        return restored;
    }

    /** 用户显式启用后先原子保存当前保留范围，以后逐条追加日志；不追补启用前已淘汰的消息。 文件提交失败不改变内存采集状态；成功后才标记启用。日志顺序由仓库锁保证。 */
    public synchronized ObjectNode enableContinuous(String id) throws IOException {
        ObjectNode source = liveCaptures.get(id);
        if (source == null
                || !CaptureState.CAPTURING.name().equals(source.path("liveState").asText())) {
            throw new IllegalArgumentException("仅可为正在采集的记录启用持续落盘");
        }
        if (source.path("continuous").asBoolean()) {
            return summary(source);
        }
        if (source.has("continuousError")) {
            throw new IllegalArgumentException("本记录落盘曾失败，请先停止并核对已保存内容，再开始新记录");
        }
        ObjectNode baseline = load(id).deepCopy();
        int first = baseline.path("firstIndex").asInt();
        int last = baseline.path("lastIndex").asInt(-1);
        baseline.put("continuous", true)
                .put("temporary", false)
                .put("diskFirstIndex", first)
                .put("diskLastIndex", last);
        save(baseline);
        source.put("continuous", true)
                .put("temporary", false)
                .put("diskFirstIndex", first)
                .put("diskLastIndex", last);
        return summary(source);
    }

    /** 握手后仅创建内存记录；用户选择保存之前不产生抓包文件或增量日志。 */
    public synchronized String createLive(String connection, String endpoint, String extensions) {
        return createLive(connection, endpoint, extensions, false);
    }

    public synchronized String createLive(
            String connection, String endpoint, String extensions, boolean continued) {
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
                || !CaptureState.CAPTURING.name().equals(capture.path("liveState").asText())) {
            throw new IllegalArgumentException("实时记录已停止");
        }
        LiveMessageBuffer buffer = liveBuffers.get(id);
        ObjectNode appended = buffer.append(message, mapper);
        ObjectNode identity =
                ConnectionIdentity.observe(appended, capture.path("dictionary"), mapper);
        if (identity != null) {
            updateIdentity(capture, identity, appended.path("connection").asText());
        }
        if (capture.path("continuous").asBoolean()) {
            try {
                // 一行一个原编号消息，不重复重写整份抓包；关闭文件后再报告该编号已写入。
                Files.writeString(
                        directory.resolve(id + ".journal"),
                        mapper.writeValueAsString(appended) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                capture.put("diskLastIndex", appended.path("index").asInt());
            } catch (IOException failure) {
                // 保留已落盘范围和内存采集，停止追加以免在截断尾部后续写并误称完整。
                capture.put("continuous", false)
                        .put("continuousError", "持续落盘失败：" + failure.getMessage());
            }
        }
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

    /** 身份分段从确认消息开始生效，不能把新身份倒灌到前面的消息；重复推送不新增分段。 */
    private void updateIdentity(ObjectNode capture, ObjectNode identity, String connectionId) {
        ObjectNode connection = null;
        for (JsonNode candidate : capture.withArray("connections")) {
            if (candidate.path("id").asText().equals(connectionId)) {
                connection = (ObjectNode) candidate;
            }
        }
        if (connection == null) {
            return;
        }
        JsonNode previous = connection.path("identity");
        if (identity.equals(previous)) {
            return;
        }
        ObjectNode currentValue = identity.deepCopy();
        currentValue.remove(List.of("sourceIndex", "sourceProtocol"));
        ObjectNode previousValue =
                previous.isObject()
                        ? ((ObjectNode) previous).deepCopy()
                        : mapper.createObjectNode();
        previousValue.remove(List.of("sourceIndex", "sourceProtocol"));
        if (currentValue.equals(previousValue)
                && !ConnectionIdentityState.PENDING
                        .code()
                        .equals(identity.path("state").asText())) {
            return;
        }
        // 登录或角色变更时解除旧备注，避免旧账号的“主号”别名跟随新账号。
        if (ConnectionIdentityState.PENDING.code().equals(identity.path("state").asText())
                || (!previous.path("roleId").asText().isEmpty()
                        && !previous.path("roleId")
                                .asText()
                                .equals(identity.path("roleId").asText()))) {
            connection.put("note", "");
        }
        connection.set("identity", identity);
        ArrayNode history = connection.withArray("identityHistory");
        history.add(identity.deepCopy());
        if (history.size() > AnalyzerLimits.MAX_IDENTITY_HISTORY) {
            history.remove(0);
            connection.put("identityHistoryTruncated", true);
        }
    }

    /** 从当前仍保留消息重算遗漏身份，不重连游戏、不改变消息解码状态，不用更早的证据回滚最新身份。 */
    public synchronized ObjectNode reidentifyConnections(String id) throws IOException {
        ObjectNode source = load(id);
        ObjectNode rebuilt = source.deepCopy();
        for (JsonNode message : rebuilt.path("messages")) {
            ObjectNode identity =
                    ConnectionIdentity.observe(message, rebuilt.path("dictionary"), mapper);
            if (identity == null) {
                continue;
            }
            for (JsonNode connection : rebuilt.path("connections")) {
                // 仅重新处理当前证据之后或同一位置的消息，不用淘汰窗口中的旧消息回滚新身份。
                if (connection.path("id").equals(message.path("connection"))
                        && identity.path("sourceIndex").asInt()
                                >= connection.path("identity").path("sourceIndex").asInt(-1)) {
                    updateIdentity(rebuilt, identity, message.path("connection").asText());
                }
            }
        }
        if (liveCaptures.containsKey(id)) {
            // 只更新连接元信息，绝不用有界内存窗口覆盖磁盘消息。
            source.set("connections", rebuilt.path("connections"));
            return summary(source);
        }
        save(rebuilt);
        Files.deleteIfExists(directory.resolve(id + ".journal"));
        Files.deleteIfExists(directory.resolve(id + ".connections"));
        return summary(rebuilt);
    }

    /** 手动备注只属于该条连接，不声明已识别账号；先写副本，保存失败不发布新状态。 */
    public synchronized ObjectNode annotateConnection(String id, String connectionId, String note)
            throws IOException {
        return annotateConnection(id, connectionId, note, null);
    }

    /** 编辑期间可能发生账号切换；提供预期身份时，必须仍对应同一身份证据才允许保存备注。 */
    public synchronized ObjectNode annotateConnection(
            String id, String connectionId, String note, JsonNode expectedIdentity)
            throws IOException {
        if (note == null
                || note.length() > AnalyzerLimits.MAX_CONNECTION_NOTE_LENGTH
                || note.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("连接备注最多 40 字，不能包含换行或控制字符");
        }
        ObjectNode source = liveCaptures.containsKey(id) ? liveCaptures.get(id) : load(id);
        ObjectNode copy = liveCaptures.containsKey(id) ? summary(source) : source.deepCopy();
        ObjectNode target = null;
        for (JsonNode connection : copy.withArray("connections")) {
            if (connection.path("id").asText().equals(connectionId)) {
                target = (ObjectNode) connection;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("记录中不存在此连接");
        }
        if (expectedIdentity != null
                && (expectedIdentity.isNull()
                        ? target.hasNonNull("identity")
                        : !target.path("identity").equals(expectedIdentity))) {
            throw new IllegalArgumentException("连接身份已变化，请重新读取并确认账号后再保存备注");
        }
        target.put("note", note.strip());
        if (liveCaptures.containsKey(id)) {
            // 持续落盘的备注单独原子保存，绝不拿内存窗口覆盖磁盘已有消息。
            if (source.has("diskFirstIndex")) {
                Path metadata = directory.resolve(id + ".connections");
                Path temporary = Files.createTempFile(directory, "connections-", ".tmp");
                try {
                    mapper.writeValue(temporary.toFile(), copy.path("connections"));
                    try {
                        Files.move(
                                temporary,
                                metadata,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            source.set("connections", copy.path("connections"));
        } else {
            save(copy);
            // 中断后的记录已完整物化；发布成功后移除旧日志与备注覆盖层，后续读取不回退到旧备注。
            Files.deleteIfExists(directory.resolve(id + ".journal"));
            Files.deleteIfExists(directory.resolve(id + ".connections"));
        }
        return summary(liveCaptures.containsKey(id) ? source : copy);
    }

    public synchronized void stopLive(String id, String state) {
        ObjectNode capture = liveCaptures.get(id);
        if (capture != null) {
            capture.put("liveState", state);
            if (capture.has("diskFirstIndex")) {
                try {
                    // 持续落盘记录在停止/断开时合并日志，记录真实终态；失败仍保留基线、日志和内存。
                    persistLive(id, capture.path("name").asText());
                } catch (IOException failure) {
                    capture.put("continuousError", "停止时合并落盘记录失败：" + failure.getMessage());
                }
            }
        }
    }

    /** 仅保存已停止的临时记录；快照发布成功之后才释放内存，失败仍可重试。 */
    public synchronized ObjectNode persistLive(String id, String name) throws IOException {
        ObjectNode capture = load(id).deepCopy();
        if (CaptureState.CAPTURING.name().equals(capture.path("liveState").asText())) {
            throw new IllegalArgumentException("请先停止采集再保存记录");
        }
        if (capture.has("diskFirstIndex")) {
            ObjectNode archive = readArchive(id);
            // 内存窗口可以淘汰，完整保存使用磁盘范围；落盘失败后内存仍保留的尾部一并补入。
            ArrayNode all = archive.withArray("messages");
            int last = all.isEmpty() ? -1 : all.path(all.size() - 1).path("index").asInt();
            for (JsonNode message : capture.path("messages")) {
                if (message.path("index").asInt() > last) {
                    if (message.path("index").asInt() != last + 1) {
                        capture.put("recoveryWarning", "落盘失败后部分消息已从内存淘汰，保存内容存在编号缺口");
                    }
                    all.add(message.deepCopy());
                    last = message.path("index").asInt();
                }
            }
            capture.set("messages", all);
            capture.put("continuous", false)
                    .put("memoryEvictedCount", capture.path("evictedCount").asLong())
                    .put("evictedCount", archive.path("evictedCount").asLong())
                    .put("diskLastIndex", last);
            if (archive.has("recoveryWarning")) {
                capture.set("recoveryWarning", archive.path("recoveryWarning"));
            }
        }
        String title = name == null || name.isBlank() ? capture.path("name").asText() : name.trim();
        if (title.length() > AnalyzerLimits.MAX_CAPTURE_NAME_LENGTH) {
            throw new IllegalArgumentException("记录名称不能超过 100 字");
        }
        capture.put("name", title).put("temporary", false);
        capture.setAll(summary(capture, false));
        for (JsonNode mark : capture.withArray("marks")) {
            ((ObjectNode) mark)
                    .put("expired", mark.path("from").asInt() < capture.path("firstIndex").asInt());
        }
        save(capture);
        Files.deleteIfExists(directory.resolve(id + ".journal"));
        Files.deleteIfExists(directory.resolve(id + ".connections"));
        liveCaptures.remove(id);
        liveBuffers.remove(id);
        return summary(capture);
    }

    /** 清空本轮临时记录的消息和标记，保留记录 ID、字典、连接及名称，绝不修改历史文件。 */
    public synchronized Set<String> clearTemporary(Set<String> ids) {
        Set<String> cleared = new LinkedHashSet<>();
        for (String id : ids) {
            ObjectNode capture = liveCaptures.get(id);
            if (capture == null || !capture.path("temporary").asBoolean()) {
                continue;
            }
            LiveMessageBuffer buffer = liveBuffers.get(id);
            buffer.clear();
            buffer.describe(capture);
            capture.putArray("messages");
            capture.putArray("marks");
            capture.put("clearedAt", Instant.now().toString());
            cleared.add(id);
        }
        return cleared;
    }

    public synchronized ObjectNode delete(String id) throws IOException {
        path(id);
        ObjectNode capture = load(id);
        if (CaptureState.CAPTURING.name().equals(capture.path("liveState").asText())) {
            throw new IllegalArgumentException("正在采集的记录不能删除，请先停止采集");
        }
        Files.deleteIfExists(directory.resolve(id + ".json"));
        Files.deleteIfExists(directory.resolve(id + ".journal"));
        Files.deleteIfExists(directory.resolve(id + ".connections"));
        Files.deleteIfExists(directory.resolve(id + ".meta"));
        liveCaptures.remove(id);
        liveBuffers.remove(id);
        if (cachedCapture != null && cachedCapture.path("id").asText().equals(id)) {
            cachedCapture = null;
        }
        return mapper.createObjectNode().put("deleted", id);
    }

    /** 列表只传输摘要，查询条件与筛选算法独立于持久化和实时缓存。 */
    public ObjectNode messages(String id, MessageQuery query) throws IOException {
        return messages(id, query, false);
    }

    /** 页面可请求同刻活动快照，MCP 等既有查询默认不携带累计协议统计，保持结果预算。 */
    public ObjectNode messages(String id, MessageQuery query, boolean includeActivity)
            throws IOException {
        return messages(id, query, includeActivity, "[]", false);
    }

    /** 字段投影与折叠仅作用当前页，旧查询契约默认不增加额外计算。 */
    public ObjectNode messages(
            String id, MessageQuery query, boolean includeActivity, String fields, boolean fold)
            throws IOException {
        var paths = new CaptureAnalysisQuery(mapper).fields(fields);
        long started = System.nanoTime();
        ObjectNode capture;
        ObjectNode metadata;
        ObjectNode activity;
        long acquired;
        long released;
        synchronized (this) {
            acquired = System.nanoTime();
            String exactProtocol = query.protocolId();
            if (exactProtocol.isBlank() && query.include().matches("id:[0-9]+")) {
                exactProtocol = query.include().substring(3);
            }
            if (!exactProtocol.isBlank() && liveBuffers.containsKey(id)) {
                capture = summary(liveCaptures.get(id));
                capture.set(
                        "messages", liveBuffers.get(id).protocolSnapshot(exactProtocol, mapper));
            } else {
                capture = queryView(id);
            }
            metadata = summary(capture);
            activity =
                    includeActivity && liveBuffers.containsKey(id)
                            ? liveBuffers.get(id).activitySnapshot(mapper)
                            : null;
            released = System.nanoTime();
        }
        ObjectNode result = messageQuery.messages(capture, query, metadata, paths, fold);
        if (includeActivity) {
            result.set(
                    "activity",
                    (activity == null
                                    ? CaptureActivityTracker.retainedSnapshot(capture, mapper)
                                    : activity)
                            .put("captureId", id));
        }
        result.put("lockWaitMs", (acquired - started) / 1_000_000.0)
                .put("snapshotMs", (released - acquired) / 1_000_000.0)
                .put("queryMs", (System.nanoTime() - released) / 1_000_000.0);
        return result;
    }

    /** 消息在追加时已防御性复制，发布后不再修改；数组也在每次读取时独立物化。 锁内只捕获消息引用与元信息，不复制大消息体；后续追加、清空和标记替换不会改写本视图。 */
    private ObjectNode queryView(String id) throws IOException {
        ObjectNode source = load(id);
        ObjectNode view = summary(source);
        view.set("messages", source.path("messages"));
        return view;
    }

    /** 统计和列表互相独立；实时记录不扫描消息体，历史记录在锁外只读重建。 */
    public ObjectNode activity(String id) throws IOException {
        ObjectNode view;
        synchronized (this) {
            if (liveBuffers.containsKey(id)) {
                return liveBuffers.get(id).activitySnapshot(mapper).put("captureId", id);
            }
            view = queryView(id);
        }
        return CaptureActivityTracker.retainedSnapshot(view, mapper).put("captureId", id);
    }

    /** 在锁内冻结保留消息，锁外按列表筛选统计通知窗口；累计活动仍独立保存。 */
    public ObjectNode activityWindow(String id, MessageQuery query) throws IOException {
        ObjectNode view;
        synchronized (this) {
            view = queryView(id);
        }
        return messageQuery.activityWindow(view, query).put("captureId", id);
    }

    /** 用户主动分析使用稳定保留视图；不持有仓库锁扫描正文。 */
    public ObjectNode analyze(String id, MessageQuery query, String fields) throws IOException {
        var analysis = new CaptureAnalysisQuery(mapper);
        var paths = analysis.fields(fields);
        ObjectNode view;
        synchronized (this) {
            view = queryView(id);
        }
        return analysis.analyze(view, query, paths);
    }

    /** 时间定位同样在锁外执行，并沿用当前筛选，不加载或改写其他记录。 */
    public ObjectNode locateTime(String id, MessageQuery query, String time, int seconds)
            throws IOException {
        ObjectNode view;
        synchronized (this) {
            view = queryView(id);
        }
        return new CaptureTimeQuery(mapper).locate(view, query, time, seconds);
    }

    /** 同连接、方向、协议的后续消息，只统计当前保留范围；不要求旧详情消息仍在缓存中。 */
    public ObjectNode latestSameProtocol(
            String id,
            String protocolId,
            String protocolName,
            String connection,
            String direction,
            int after)
            throws IOException {
        ObjectNode view;
        synchronized (this) {
            if (!protocolId.isBlank() && liveBuffers.containsKey(id)) {
                view = summary(liveCaptures.get(id));
                view.set("messages", liveBuffers.get(id).protocolSnapshot(protocolId, mapper));
            } else {
                view = queryView(id);
            }
        }
        if (after < 0
                || connection.isBlank()
                || direction.isBlank()
                || (protocolId.isBlank() && protocolName.isBlank())) {
            throw new IllegalArgumentException("同协议后续消息查询缺少连接、方向或协议条件");
        }
        int count = 0;
        JsonNode latest = null;
        for (JsonNode message : view.path("messages")) {
            if (message.path("index").asInt() <= after
                    || !connection.equals(message.path("connection").asText())
                    || !direction.equals(message.path("direction").asText())
                    || !(protocolId.isBlank()
                            ? protocolName.equals(message.path("protocolName").asText())
                            : protocolId.equals(message.path("protocolId").asText()))) {
                continue;
            }
            count++;
            latest = message;
        }
        ObjectNode result =
                mapper.createObjectNode()
                        .put("count", count)
                        .put("truncated", (long) after + 1 < view.path("firstIndex").asInt());
        if (latest != null) {
            ObjectNode item = result.putObject("latest");
            for (String field :
                    List.of(
                            "index",
                            "protocolId",
                            "protocolName",
                            "connection",
                            "direction",
                            "time",
                            "status",
                            "bytes",
                            "wireBytes",
                            "ordinal",
                            "frameId",
                            "compressed")) {
                if (latest.has(field)) {
                    item.set(field, latest.get(field).deepCopy());
                }
            }
        }
        return result;
    }

    /** 同一连接、方向及协议的前一条消息；不应用列表筛选，不推断请求响应关系。 */
    public JsonNode previousSameProtocol(String id, int index) throws IOException {
        ObjectNode view;
        synchronized (this) {
            view = queryView(id);
        }
        JsonNode messages = view.path("messages");
        JsonNode target = null;
        for (int position = messages.size() - 1; position >= 0; position--) {
            JsonNode candidate = messages.path(position);
            if (candidate.path("index").asInt() == index) {
                target = candidate;
                continue;
            }
            if (target == null || candidate.path("index").asInt() >= index) {
                continue;
            }
            if (candidate.path("connection").equals(target.path("connection"))
                    && candidate.path("direction").equals(target.path("direction"))
                    && (target.hasNonNull("protocolId")
                            ? candidate.path("protocolId").equals(target.path("protocolId"))
                            : target.hasNonNull("protocolName")
                                    && candidate
                                            .path("protocolName")
                                            .equals(target.path("protocolName")))) {
                return candidate.deepCopy();
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("目标消息已清空或淘汰，请刷新列表");
        }
        throw new IllegalArgumentException("保留范围内没有同连接、同方向、同协议的上一条消息");
    }

    public synchronized JsonNode detail(String id, int index) throws IOException {
        ObjectNode capture;
        JsonNode message;
        if (liveBuffers.containsKey(id)) {
            capture = liveCaptures.get(id);
            message = liveBuffers.get(id).messageAt(index);
        } else {
            capture = load(id);
            JsonNode messages = capture.path("messages");
            int low = 0;
            int high = messages.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (messages.path(middle).path("index").asInt() < index) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            message =
                    low < messages.size() && messages.path(low).path("index").asInt() == index
                            ? messages.path(low)
                            : null;
        }
        if (message == null) {
            throw new IllegalArgumentException("消息不存在、已被清空或已被缓存淘汰，请刷新列表");
        }
        ObjectNode detail = message.deepCopy();
        String protocolId = detail.path("protocolId").asText();
        detail.set(
                "schema",
                capture.path("dictionary").path("definitions").path(protocolId).deepCopy());
        detail.set(
                "definition",
                capture.path("dictionary").path("messages").path(protocolId).deepCopy());
        detail.set("catalog", capture.path("catalog").deepCopy());
        for (JsonNode connection : capture.path("connections")) {
            if (!connection.path("id").equals(detail.path("connection"))) {
                continue;
            }
            JsonNode identity = identityAt(connection, index);
            if (identity != null) {
                detail.set("connectionIdentity", identity.deepCopy());
            }
        }
        return detail;
    }

    /** 按证据编号选择身份，未知的早期区间不使用连接最新身份补齐。 */
    private JsonNode identityAt(JsonNode connection, int index) {
        JsonNode result = null;
        for (JsonNode event : connection.path("identityHistory")) {
            if (event.path("sourceIndex").asInt() <= index) {
                result = event;
            }
        }
        return result;
    }

    /** 保存人工标注的操作区间；它只表示时间范围，不宣称区间内所有推送均由该操作触发。 */
    public synchronized ObjectNode mark(String id, String name, int from, int to)
            throws IOException {
        ObjectNode capture = read(id);
        if (name.isBlank()
                || name.length() > AnalyzerLimits.MAX_CAPTURE_NAME_LENGTH
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
        return summary(capture, true);
    }

    /** 磁盘快照的统计必须依据该快照，不能被同 ID 的活动内存窗口覆盖。 */
    private ObjectNode summary(ObjectNode capture, boolean useLiveBuffer) {
        ObjectNode result = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = capture.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"messages".equals(field.getKey()) && !"dictionary".equals(field.getKey())) {
                result.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        if (useLiveBuffer && liveBuffers.containsKey(capture.path("id").asText())) {
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
            synchronized (this) {
                cachedCapture = capture;
            }
            // 摘要是可重建缓存；写入失败不把已成功发布的完整抓包误报为失败。
            Path metadata = directory.resolve(capture.path("id").asText() + ".meta");
            Path metadataTemporary = Files.createTempFile(directory, "metadata-", ".tmp");
            try {
                ObjectNode metadataValue;
                synchronized (this) {
                    metadataValue = summary(capture, false);
                }
                mapper.writeValue(metadataTemporary.toFile(), metadataValue);
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
