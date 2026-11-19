package com.lashouwan.protolens.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.ConnectionIdentityState;
import com.lashouwan.protolens.enums.DecodeStatus;
import com.lashouwan.protolens.enums.MessageDirection;
import com.lashouwan.protolens.enums.ThriftType;

import java.util.regex.Pattern;

/** 从已确认的当前玩家协议提取身份；仓库负责串行发布，导入时也可用于尚未发布的局部记录。不扫描任意 roleId，不复制登录凭证。 */
final class ConnectionIdentity {
    private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,19}");

    private ConnectionIdentity() {}

    /** 仅使用已确认的命名字段。部分解码允许其他未知字段存在，身份字段类型不匹配时解码器会改名为 $field_N，不能冒用。 */
    static ObjectNode observe(JsonNode message, JsonNode dictionary, ObjectMapper mapper) {
        String status = message.path("status").asText();
        if (!DecodeStatus.DECODED.name().equals(status)
                && !DecodeStatus.PARTIAL.name().equals(status)) {
            return null;
        }
        String id = message.path("protocolId").asText();
        String name = dictionary.path("messages").path(id).path("name").asText();
        if (!name.equals(message.path("protocolName").asText())) {
            return null;
        }
        JsonNode fields = dictionary.path("definitions").path(id);
        if ("CgLogin".equals(name)
                && DecodeStatus.DECODED.name().equals(status)
                && MessageDirection.CS.name().equals(message.path("direction").asText())
                && scalarField(fields, "1", "uid", ThriftType.I64)
                && scalarField(fields, "2", "roleId", ThriftType.I64)) {
            return mapper.createObjectNode()
                    .put("state", ConnectionIdentityState.PENDING.code())
                    .put("sourceProtocol", name)
                    .put("sourceIndex", message.path("index").asInt());
        }
        JsonNode nested = fields.path("1");
        if (!"GcPlayerInfo".equals(name)
                || !MessageDirection.SC.name().equals(message.path("direction").asText())
                || !"playerInfo".equals(nested.path(0).asText())
                || nested.path(1).path(0).asInt() != ThriftType.STRUCT.code()
                || !"PsPlayerInfo".equals(nested.path(1).path(1).asText())) {
            return null;
        }
        JsonNode schema = dictionary.path("definitions").path("PsPlayerInfo");
        JsonNode player = message.path("body").path("playerInfo");
        if (!scalarField(schema, "1", "roleId", ThriftType.I64)) {
            return null;
        }
        String role = numericId(player.path("roleId"));
        if (role.isEmpty()) {
            return null;
        }
        ObjectNode identity =
                mapper.createObjectNode()
                        .put("state", ConnectionIdentityState.IDENTIFIED.code())
                        .put("roleId", role)
                        .put("sourceProtocol", name)
                        .put("sourceIndex", message.path("index").asInt());
        if (scalarField(schema, "50", "accId", ThriftType.I64)) {
            putId(identity, "accountId", player.path("accId"));
        }
        if (scalarField(schema, "23", "serverId", ThriftType.I32)) {
            putId(identity, "serverId", player.path("serverId"));
        }
        if (scalarField(schema, "2", "name", ThriftType.STRING)
                && player.path("name").isTextual()) {
            String nickname = player.path("name").asText().strip();
            if (nickname.length() <= AnalyzerLimits.MAX_IDENTITY_NICKNAME_LENGTH
                    && nickname.chars().noneMatch(Character::isISOControl)) {
                identity.put("nickname", nickname);
            }
        }
        return identity;
    }

    private static boolean scalarField(
            JsonNode fields, String number, String name, ThriftType type) {
        return name.equals(fields.path(number).path(0).asText())
                && fields.path(number).path(1).isIntegralNumber()
                && fields.path(number).path(1).asInt() == type.code();
    }

    /** ID 全程保存为十进制字符串，避免前端 JS 将 64 位角色编号转换成不精确浮点数。 */
    private static String numericId(JsonNode value) {
        if (!value.isIntegralNumber() && !value.isTextual()) {
            return "";
        }
        String text = value.asText();
        return NUMERIC_ID.matcher(text).matches() ? text : "";
    }

    private static void putId(ObjectNode target, String key, JsonNode value) {
        String id = numericId(value);
        if (!id.isEmpty()) {
            target.put(key, id);
        }
    }
}
