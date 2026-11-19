package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** 一条实时连接的有界缓存；由 CaptureStore 的锁保护，淘汰不改变递增消息编号。 */
final class LiveMessageBuffer {
    private final Deque<ObjectNode> messages = new ArrayDeque<>();
    private final Deque<Integer> sizes = new ArrayDeque<>();

    /** 协议 ID 到仍保留消息的有序队列，与主队列同时追加、淘汰和清空。 */
    private final Map<String, Deque<ObjectNode>> protocolMessages = new HashMap<>();

    /** 原始消息序号索引，详情读取不为单条消息物化整份实时队列。 */
    private final Map<Integer, ObjectNode> indexedMessages = new HashMap<>();

    /** 同时限制条数和序列化字节，避免少量大包突破条数预算；不等同 JVM 精确堆用量。 */
    private final int maxMessages;

    private final long maxBytes;
    private long bytes;
    private int nextIndex;
    private long evicted;

    /** 用户主动清掉的消息数与清空代次，独立于容量淘汰；原始消息编号不重用。 */
    private long cleared;

    private long clearVersion;
    private final ObjectNode counts;

    LiveMessageBuffer(int maxMessages, long maxBytes, ObjectMapper mapper) {
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        counts = mapper.createObjectNode();
    }

    ObjectNode append(ObjectNode message, ObjectMapper mapper) throws IOException {
        ObjectNode item = message.deepCopy();
        item.put("index", nextIndex);
        int size = mapper.writeValueAsBytes(item).length;
        nextIndex++;
        messages.addLast(item);
        indexedMessages.put(item.path("index").asInt(), item);
        protocolMessages
                .computeIfAbsent(item.path("protocolId").asText(), key -> new ArrayDeque<>())
                .addLast(item);
        sizes.addLast(size);
        bytes += size;
        updateCount(item, 1);
        while (messages.size() > maxMessages || bytes > maxBytes) {
            ObjectNode removed = messages.removeFirst();
            indexedMessages.remove(removed.path("index").asInt());
            String protocolId = removed.path("protocolId").asText();
            Deque<ObjectNode> protocolQueue = protocolMessages.get(protocolId);
            protocolQueue.removeFirst();
            if (protocolQueue.isEmpty()) {
                protocolMessages.remove(protocolId);
            }
            updateCount(removed, -1);
            bytes -= sizes.removeFirst();
            evicted++;
        }
        return item;
    }

    /** 清除已保留消息，不重置递增编号；旧详情请求不会误命中新消息。 */
    void clear() {
        cleared += messages.size();
        messages.clear();
        sizes.clear();
        protocolMessages.clear();
        indexedMessages.clear();
        bytes = 0;
        counts.removeAll();
        clearVersion++;
    }

    private void updateCount(ObjectNode item, int delta) {
        String status = item.path("status").asText();
        counts.put(status, counts.path(status).asInt() + delta);
    }

    ArrayNode snapshot(ObjectMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
        messages.forEach(result::add);
        return result;
    }

    ArrayNode protocolSnapshot(String protocolId, ObjectMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
        Deque<ObjectNode> matching = protocolMessages.get(protocolId);
        if (matching != null) {
            matching.forEach(result::add);
        }
        return result;
    }

    ObjectNode messageAt(int index) {
        return indexedMessages.get(index);
    }

    void describe(ObjectNode capture) {
        capture.put("messageCount", messages.size())
                .put("totalReceived", nextIndex)
                .put(
                        "firstIndex",
                        messages.isEmpty() ? nextIndex : messages.getFirst().path("index").asInt())
                .put("lastIndex", nextIndex - 1)
                .put("evictedCount", evicted)
                .put("clearedCount", cleared)
                .put("clearVersion", clearVersion)
                .put("cacheBytes", bytes)
                .put("maxMessages", maxMessages)
                .put("maxBytes", maxBytes);
        capture.set("counts", counts.deepCopy());
    }
}
