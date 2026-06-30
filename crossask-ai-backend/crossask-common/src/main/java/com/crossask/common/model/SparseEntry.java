package com.crossask.common.model;

/**
 * 稀疏向量单条目（DashScope sparse_embedding 元素）。
 *
 * @param index token id（DashScope 词表索引）
 * @param value 权重
 */
public record SparseEntry(int index, float value) {
}
