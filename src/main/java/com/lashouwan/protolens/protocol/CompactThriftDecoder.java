package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.CompactType;
import com.lashouwan.protolens.enums.ThriftType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** 解码客户端实际使用的 Thrift Compact 结构体；不包含 RPC MessageBegin 包装。 */
public final class CompactThriftDecoder {
    private final ProtocolCatalog catalog;
    private final ObjectMapper mapper;
    private final byte[] data;

    /** 当前结构读取游标，单位为字节；每条消息使用独立解码器。 */
    private int offset;

    /** 递归共享读取预算，限制恶意容器和未知字段的总量。 */
    private int budget = AnalyzerLimits.MAX_THRIFT_READ_OPERATIONS;

    private final ArrayNode warnings;

    public CompactThriftDecoder(ProtocolCatalog catalog, ObjectMapper mapper, byte[] data) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.data = data.clone();
        this.warnings = mapper.createArrayNode();
    }

    public ObjectNode decode(int protocolId) {
        ObjectNode body = structure(catalog.fields(Integer.toString(protocolId)), 0);
        if (offset != data.length) {
            throw new IllegalArgumentException("Thrift 消息体仍有 " + (data.length - offset) + " 字节未消费");
        }
        return body;
    }

    public ArrayNode warnings() {
        return warnings.deepCopy();
    }

    /** 字段号按结构体独立累计，未知字段保留编号与值，避免版本不匹配时静默丢失。 */
    private ObjectNode structure(JsonNode schema, int depth) {
        checkDepth(depth);
        ObjectNode result = mapper.createObjectNode();
        int previous = 0;
        while (true) {
            int header = octet();
            if (header == 0) {
                return result;
            }
            consumeBudget();
            int type = header & 15;
            if (type == 0) {
                throw new IllegalArgumentException("非法 STOP 字段头");
            }
            int fieldId =
                    (header >>> 4) == 0 ? Math.toIntExact(zigzag()) : previous + (header >>> 4);
            previous = fieldId;
            JsonNode definition = schema.path(Integer.toString(fieldId));
            String name = definition.path(0).asText("$field_" + fieldId);
            JsonNode expected = definition.path(1);
            if (definition.isMissingNode()) {
                warnings.add("未知字段 " + fieldId + "，按 wire 类型保留");
            } else if (thriftType(type)
                    != (expected.isArray() ? expected.path(0).asInt() : expected.asInt())) {
                warnings.add("字段 " + name + " 类型与字典不一致，按 wire 类型读取");
                name = "$field_" + fieldId;
                expected = mapper.missingNode();
            }
            if (result.has(name)) {
                throw new IllegalArgumentException("重复字段 " + fieldId);
            }
            result.set(name, value(type, expected, depth + 1, true));
        }
    }

    private JsonNode value(int type, JsonNode schema, int depth, boolean fieldBoolean) {
        checkDepth(depth);
        consumeBudget();
        return switch (CompactType.fromCode(type)) {
            case BOOLEAN_TRUE, BOOLEAN_FALSE -> {
                int booleanValue = fieldBoolean ? type : octet();
                if (booleanValue != CompactType.BOOLEAN_TRUE.code()
                        && booleanValue != CompactType.BOOLEAN_FALSE.code()) {
                    throw new IllegalArgumentException("非法布尔值");
                }
                yield mapper.getNodeFactory()
                        .booleanNode(booleanValue == CompactType.BOOLEAN_TRUE.code());
            }
            case BYTE -> mapper.getNodeFactory().numberNode((byte) octet());
            case I16, I32 -> {
                long number = zigzag();
                if (number < Integer.MIN_VALUE
                        || number > Integer.MAX_VALUE
                        || (type == CompactType.I16.code()
                                && (number < Short.MIN_VALUE || number > Short.MAX_VALUE))) {
                    throw new IllegalArgumentException("Thrift 整数越界");
                }
                yield mapper.getNodeFactory().numberNode((int) number);
            }
            // I64 总是以十进制字符串输出，避免浏览器超过 2^53 后丢精度。
            case I64 -> mapper.getNodeFactory().textNode(Long.toString(zigzag()));
            case DOUBLE -> {
                require(8);
                double number =
                        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
                offset += 8;
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("非有限浮点值");
                }
                yield mapper.getNodeFactory().numberNode(number);
            }
            case BINARY ->
                    binary(
                            schema.isArray()
                                    && schema.path(0).asInt() == ThriftType.STRING.code()
                                    && schema.path(1).asInt() == 1);
            case LIST, SET -> {
                int header = octet();
                int size = header >>> 4;
                if (size == 15) {
                    size = size();
                }
                checkContainer(size);
                int elementType = header & 15;
                JsonNode elementSchema = schema.isArray() ? schema.path(1) : mapper.missingNode();
                validateElementType(elementType, elementSchema, size);
                ArrayNode list = mapper.createArrayNode();
                for (int index = 0; index < size; index++) {
                    list.add(value(elementType, elementSchema, depth + 1, false));
                }
                yield list;
            }
            case MAP -> {
                int count = size();
                checkContainer(count);
                int types = count == 0 ? 0 : octet();
                JsonNode keySchema = schema.isArray() ? schema.path(1) : mapper.missingNode();
                JsonNode valueSchema = schema.isArray() ? schema.path(2) : mapper.missingNode();
                validateElementType(types >>> 4, keySchema, count);
                validateElementType(types & 15, valueSchema, count);
                ArrayNode entries = mapper.createArrayNode();
                for (int index = 0; index < count; index++) {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.set("key", value(types >>> 4, keySchema, depth + 1, false));
                    entry.set("value", value(types & 15, valueSchema, depth + 1, false));
                    entries.add(entry);
                }
                // 条目数组保留 I64/结构体键及原始顺序，避免 JSON 对象键强制转字符串造成覆盖。
                ObjectNode map = mapper.createObjectNode();
                map.set("$map", entries);
                yield map;
            }
            case STRUCT ->
                    structure(
                            schema.isArray()
                                    ? catalog.fields(schema.path(1).asText())
                                    : mapper.missingNode(),
                            depth + 1);
        };
    }

    private JsonNode binary(boolean explicitBinary) {
        int length = size();
        require(length);
        byte[] bytes = Arrays.copyOfRange(data, offset, offset + length);
        offset += length;
        // 新版字典 [11,1] 明确声明二进制；即使恰好能按 UTF-8 读取，也不能冒充普通文本。
        if (explicitBinary) {
            return mapper.createObjectNode()
                    .put("$base64", Base64.getEncoder().encodeToString(bytes));
        }
        try {
            String value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            return mapper.getNodeFactory().textNode(value);
        } catch (CharacterCodingException failure) {
            ObjectNode binary = mapper.createObjectNode();
            binary.put("$base64", Base64.getEncoder().encodeToString(bytes));
            warnings.add("STRING 字段不是合法 UTF-8，已保留二进制 Base64");
            return binary;
        }
    }

    private void validateElementType(int wire, JsonNode schema, int size) {
        if (size > 0
                && !schema.isMissingNode()
                && thriftType(wire)
                        != (schema.isArray() ? schema.path(0).asInt() : schema.asInt())) {
            throw new IllegalArgumentException("容器元素类型与字典不一致");
        }
    }

    private int thriftType(int compact) {
        return CompactType.fromCode(compact).schemaType().code();
    }

    private int octet() {
        require(1);
        return data[offset++] & 255;
    }

    private long varint() {
        long result = 0;
        for (int index = 0; index < 10; index++) {
            int next = octet();
            if (index == 9 && (next & 254) != 0) {
                throw new IllegalArgumentException("varint 超出 64 位");
            }
            result |= (long) (next & 127) << (7 * index);
            if ((next & 128) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("未结束的 varint");
    }

    private long zigzag() {
        long raw = varint();
        return (raw >>> 1) ^ -(raw & 1);
    }

    private int size() {
        long number = varint();
        if (number < 0 || number > AnalyzerLimits.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("字段长度越界");
        }
        return (int) number;
    }

    private void require(int size) {
        if (size < 0 || size > data.length - offset) {
            throw new IllegalArgumentException("消息体截断，偏移 " + offset);
        }
    }

    private void consumeBudget() {
        if (--budget < 0) {
            throw new IllegalArgumentException("字段读取预算耗尽");
        }
    }

    private void checkDepth(int depth) {
        if (depth > AnalyzerLimits.MAX_THRIFT_DEPTH) {
            throw new IllegalArgumentException("结构体嵌套超过 64 层");
        }
    }

    private void checkContainer(int size) {
        if (size > AnalyzerLimits.MAX_THRIFT_CONTAINER_ELEMENTS) {
            throw new IllegalArgumentException("容器元素过多");
        }
    }
}
