package com.crossask.common.model;

/**
 * crawler 输出，cleaner/splitter 的输入
 */
public class RawDocument {

    private String url;
    private String title;
    private String htmlContent;
    private String contentType;

    public RawDocument() {
    }

    public RawDocument(String url, String title, String htmlContent, String contentType) {
        this.url = url;
        this.title = title;
        this.htmlContent = htmlContent;
        this.contentType = contentType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public void setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String toString() {
        return "RawDocument{url='" + url + "', title='" + title + "', contentType='" + contentType + "'}";
    }
}
