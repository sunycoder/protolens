package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** 一条实时连接的有界缓存；由 CaptureStore 的锁保护，淘汰不改变递增消息编号。 */
final class LiveMessageBuffer {
    private final Deque<ObjectNode> messages = new ArrayDeque<>();
    private final Deque<Integer> sizes = new ArrayDeque<>();

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

    void append(ObjectNode message, ObjectMapper mapper) throws IOException {
        ObjectNode item = message.deepCopy();
        item.put("index", nextIndex);
        int size = mapper.writeValueAsBytes(item).length;
        nextIndex++;
        messages.addLast(item);
        sizes.addLast(size);
        bytes += size;
        updateCount(item, 1);
        while (messages.size() > maxMessages || bytes > maxBytes) {
            updateCount(messages.removeFirst(), -1);
            bytes -= sizes.removeFirst();
            evicted++;
        }
    }

    /** 清除已保留消息，不重置递增编号；旧详情请求不会误命中新消息。 */
    void clear() {
        cleared += messages.size();
        messages.clear();
        sizes.clear();
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
