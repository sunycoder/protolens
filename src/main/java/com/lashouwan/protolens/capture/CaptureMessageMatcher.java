package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.lashouwan.protolens.enums.MessageFilterCondition;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** 消息查询和空结果诊断共用同一过滤语义，诊断只给出有实际消息作证的单条件解除方案。 */
final class CaptureMessageMatcher {
    static final String ATTENTION_STATUS = "ATTENTION";

    private CaptureMessageMatcher() {}

    static int metadataFailures(JsonNode node, MessageQuery query) {
        int failures = 0;
        int index = node.path("index").asInt();
        if (index < query.from() || index > query.to()) {
            failures |= MessageFilterCondition.RANGE.mask();
        }
        if (!query.direction().isBlank()
                && !query.direction().equals(node.path("direction").asText())) {
            failures |= MessageFilterCondition.DIRECTION.mask();
        }
        if (!query.status().isBlank()
                && !(ATTENTION_STATUS.equals(query.status())
                        ? CaptureActivityTracker.attention(node.path("status").asText())
                        : query.status().equals(node.path("status").asText()))) {
            failures |= MessageFilterCondition.STATUS.mask();
        }
        if (!query.connection().isBlank()
                && !query.connection().equals(node.path("connection").asText())) {
            failures |= MessageFilterCondition.CONNECTION.mask();
        }
        if (!query.protocolId().isBlank()
                && !query.protocolId().equals(node.path("protocolId").asText())) {
            failures |= MessageFilterCondition.PROTOCOL_ID.mask();
        }
        if (!query.messageIndex().isBlank()
                && !query.messageIndex().equals(Integer.toString(index))) {
            failures |= MessageFilterCondition.MESSAGE_INDEX.mask();
        }
        String protocol =
                node.path("protocolName").asText() + " " + node.path("protocolId").asText();
        if (query.hideHeartbeat() && protocol.contains("SynchronizeTime")) {
            failures |= MessageFilterCondition.HEARTBEAT.mask();
        }
        if (!query.include().isBlank() && !matchesProtocol(node, protocol, query.include())) {
            failures |= MessageFilterCondition.INCLUDE.mask();
        }
        if (!query.exclude().isBlank() && matchesProtocol(node, protocol, query.exclude())) {
            failures |= MessageFilterCondition.EXCLUDE.mask();
        }
        return failures;
    }

    static boolean keywordMatches(JsonNode node, String query) {
        if (query.isBlank()) {
            return true;
        }
        String searchable =
                node.path("protocolName").asText()
                        + " "
                        + node.path("protocolId").asText()
                        + " "
                        + node.path("body");
        return searchable.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    static boolean matchesProtocol(JsonNode message, String protocol, String patterns) {
        String value = protocol.toLowerCase(Locale.ROOT);
        for (String pattern : patterns.split("[,，\\s]+")) {
            if (pattern.startsWith("id:")) {
                if (message.hasNonNull("protocolId")
                        && pattern.substring(3).equals(message.path("protocolId").asText())) {
                    return true;
                }
                continue;
            }
            if (pattern.startsWith("name:")) {
                if (pattern.substring(5).equals(message.path("protocolName").asText())) {
                    return true;
                }
                continue;
            }
            if (!pattern.isBlank() && value.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String excludedTokens(JsonNode message, String patterns) {
        String protocol =
                message.path("protocolName").asText() + " " + message.path("protocolId").asText();
        return Arrays.stream(patterns.split("[,，\\s]+"))
                .filter(token -> !token.isBlank() && matchesProtocol(message, protocol, token))
                .collect(Collectors.joining(","));
    }
}
