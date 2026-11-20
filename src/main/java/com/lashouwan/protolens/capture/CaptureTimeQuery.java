package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按记录中原始时钟定位，不把采集时间误当成服务端时间或推断日期、时区。 */
public final class CaptureTimeQuery {
    private static final long DAY_MILLIS = 86_400_000;
    private static final int MAX_MESSAGES = 200_000;
    private static final long MAX_SCAN_NANOS = 250_000_000L;
    private static final Pattern CLOCK =
            Pattern.compile("(?:^|[T ])(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)");
    private final ObjectMapper mapper;

    public CaptureTimeQuery(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 仅使用列表当前筛选中的保留消息；相同时刻多次出现时定位最早一条并明确歧义。 */
    public ObjectNode locate(ObjectNode capture, MessageQuery query, String clock, int seconds) {
        if (!clock.matches("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?")) {
            throw new IllegalArgumentException("请输入 HH:mm:ss 或 HH:mm:ss.SSS");
        }
        if (seconds < 0 || seconds > 60) {
            throw new IllegalArgumentException("时间窗口须为 0～60 秒");
        }
        long target;
        try {
            target = LocalTime.parse(clock).toNanoOfDay() / 1_000_000;
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("请输入 HH:mm:ss 或 HH:mm:ss.SSS");
        }
        List<JsonNode> visible = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        int closest = -1, ties = 0, visited = 0;
        long distance = Long.MAX_VALUE;
        boolean truncated = false;
        long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        for (JsonNode message : capture.path("messages")) {
            if (++visited > MAX_MESSAGES || System.nanoTime() > deadline) {
                truncated = true;
                break;
            }
            if (CaptureMessageMatcher.metadataFailures(message, query) != 0
                    || !CaptureMessageMatcher.keywordMatches(message, query.query())) {
                continue;
            }
            var match = CLOCK.matcher(message.path("time").asText());
            if (!match.find()) {
                continue;
            }
            long time;
            try {
                time = LocalTime.parse(match.group(1)).toNanoOfDay() / 1_000_000;
            } catch (DateTimeParseException ignored) {
                continue;
            }
            long current = distance(time, target);
            if (current < distance) {
                distance = current;
                closest = visible.size();
                ties = 1;
            } else if (current == distance) {
                ties++;
            }
            visible.add(message);
            times.add(time);
        }
        ObjectNode result =
                mapper.createObjectNode()
                        .put("found", closest >= 0)
                        .put("truncated", truncated)
                        .put("ties", ties);
        if (closest < 0) {
            return result;
        }
        int from = closest, to = closest;
        // 只在所选时刻相邻的连续片段扩展，不把其他日期的相同时刻拼到同一区间。
        if (seconds > 0) {
            while (from > 0
                    && distance(times.get(from - 1), times.get(closest)) <= seconds * 1000L) {
                from--;
            }
            while (to + 1 < times.size()
                    && distance(times.get(to + 1), times.get(closest)) <= seconds * 1000L) {
                to++;
            }
        }
        return result.put("index", visible.get(closest).path("index").asInt())
                .put("from", visible.get(from).path("index").asInt())
                .put("to", visible.get(to).path("index").asInt())
                .put("time", visible.get(closest).path("time").asText())
                .put("distanceMs", distance);
    }

    private long distance(long left, long right) {
        long difference = Math.abs(left - right);
        return Math.min(difference, DAY_MILLIS - difference);
    }
}
