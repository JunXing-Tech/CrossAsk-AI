package com.crossask.api.chat;

import com.crossask.common.model.ProductItem;
import com.crossask.common.model.Source;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v1.1 请求级 sources/products 收集器（替代 ThreadLocal 的 ToolCallContext）。
 * <p>
 * 流式路径下 Spring AI 的 stream() 会在 Reactor worker 线程执行工具调用，
 * ThreadLocal 不可靠。改用请求级对象，通过 ChatClient.toolContext 注入到工具，
 * 工具直接调 addSources/addProducts。
 * <p>
 * 用 {@link CopyOnWriteArrayList} 保证多线程并发安全。
 */
public class SourceCollector {

    public static final String CONTEXT_KEY = "collector";

    private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ProductItem> products = new CopyOnWriteArrayList<>();

    public void addSources(List<Source> list) {
        if (list != null && !list.isEmpty()) sources.addAll(list);
    }

    public void addProducts(List<ProductItem> list) {
        if (list != null && !list.isEmpty()) products.addAll(list);
    }

    public List<Source> getSources() {
        return List.copyOf(sources);
    }

    public List<ProductItem> getProducts() {
        return List.copyOf(products);
    }

    public boolean isEmpty() {
        return sources.isEmpty() && products.isEmpty();
    }
}
