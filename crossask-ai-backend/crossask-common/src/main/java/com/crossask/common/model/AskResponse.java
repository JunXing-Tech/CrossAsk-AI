package com.crossask.common.model;

import java.util.List;

/**
 * /ask 响应体。
 * v0.7：新增 {@code products} 字段，承载商品类问题的结构化数据。
 */
public class AskResponse {

    private String answer;
    private List<Source> sources;
    private List<ProductItem> products;

    public AskResponse() {
    }

    /** 兼容 v0.6 之前的构造器。 */
    public AskResponse(String answer, List<Source> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public AskResponse(String answer, List<Source> sources, List<ProductItem> products) {
        this.answer = answer;
        this.sources = sources;
        this.products = products;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public List<ProductItem> getProducts() {
        return products;
    }

    public void setProducts(List<ProductItem> products) {
        this.products = products;
    }
}
