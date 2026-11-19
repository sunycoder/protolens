package com.lashouwan.protolens.protocol;

/** 反向结构引用；ownerKey 是引用者的定义键，path 保留列表、Map 中的字段位置。 */
record ProtocolCatalogReference(String ownerKey, String path) {}
