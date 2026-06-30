package com.crossask.common.model;

/**
 * splitter 输出，indexer 存入 Qdrant 的向量 payload
 */
public class Document {

    private String docId;
    private String sourceUrl;
    private String sourceTitle;
    private int chunkIndex;
    private String content;
    private String contentType;

    public Document() {
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String toString() {
        return "Document{docId='" + docId + "', sourceUrl='" + sourceUrl + "', chunkIndex=" + chunkIndex
                + ", contentLength=" + (content != null ? content.length() : 0) + "}";
    }
}
