package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** 目录内的协议或结构体；字段节点只由所属不可变索引读取，不直接返回给 HTTP 调用方。 */
record ProtocolCatalogEntry(
        String key, String name, String direction, boolean message, JsonNode fields, int line) {}
