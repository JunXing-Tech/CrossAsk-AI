package com.crossask.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.7 LLM Function Calling 工具调用收集器（详见 Agent.md 14.6.5）。
 * <p>
 * Spring AI 的 {@code chatClient.prompt().call().content()} 只返回文本；
 * 工具被 LLM 调用时把返回的结构化数据（sources / products）写入本 ThreadLocal，
 * AskService 在 ask() 末尾取出装到 AskResponse 一并返回给前端。
 * <p>
 * 单次 ask() 入口必须先 {@link #reset()}，避免线程池复用串数据。
 */
public final class ToolCallContext {

    private static final ThreadLocal<List<Source>> SOURCES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<ProductItem>> PRODUCTS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> TOOL_CALLED = ThreadLocal.withInitial(() -> false);

    private ToolCallContext() {}

    public static void reset() {
        SOURCES.get().clear();
        PRODUCTS.get().clear();
        TOOL_CALLED.set(false);
    }

    /** 标记本次请求确实触发了工具调用（区分"meta 问题免工具"与"调了工具但返回空"）。 */
    public static void markToolCalled() {
        TOOL_CALLED.set(true);
    }

    /** 是否有工具被 LLM 调用过。 */
    public static boolean isToolCalled() {
        return TOOL_CALLED.get();
    }

    public static void addSources(List<Source> list) {
        if (list != null && !list.isEmpty()) {
            SOURCES.get().addAll(list);
        }
    }

    public static void addProducts(List<ProductItem> list) {
        if (list != null && !list.isEmpty()) {
            PRODUCTS.get().addAll(list);
        }
    }

    public static List<Source> getSources() {
        return List.copyOf(SOURCES.get());
    }

    public static List<ProductItem> getProducts() {
        return List.copyOf(PRODUCTS.get());
    }

    public static boolean isEmpty() {
        return SOURCES.get().isEmpty() && PRODUCTS.get().isEmpty();
    }
}
