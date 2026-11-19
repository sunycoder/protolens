package com.lashouwan.protolens.protocol;

/** 一条查询词的最佳匹配证据；rank 越小越优先，path 是从结果定义开始的可定位字段路径。 */
record ProtocolSearchHit(int rank, String reason, String path) {}
