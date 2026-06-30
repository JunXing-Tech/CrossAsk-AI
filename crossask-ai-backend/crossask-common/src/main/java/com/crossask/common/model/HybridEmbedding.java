package com.crossask.common.model;

import java.util.List;

/**
 * DashScope text-embedding-v4 在 output_type=dense&sparse 下的混合向量结果。
 *
 * @param dense  稠密向量（1024 维）
 * @param sparse 稀疏向量条目列表；若模型未返回 sparse_embedding，则为空列表
 */
public record HybridEmbedding(float[] dense, List<SparseEntry> sparse) {
}
