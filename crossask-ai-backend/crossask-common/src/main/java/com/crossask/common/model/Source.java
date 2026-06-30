package com.crossask.common.model;

/**
 * /ask 响应中的来源信息
 */
public class Source {

    private String sourceUrl;
    private String sourceTitle;
    private String snippet;

    public Source() {
    }

    public Source(String sourceUrl, String sourceTitle, String snippet) {
        this.sourceUrl = sourceUrl;
        this.sourceTitle = sourceTitle;
        this.snippet = snippet;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}
