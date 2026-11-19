package com.lashouwan.protolens.protocol;

/** 反向引用遍历的待处理证据；多起点共用一次遍历，避免常见字段名触发重复扫描整个引用图。 */
record ProtocolQuerySeed(String key, String path, String reason, int rank) {}
