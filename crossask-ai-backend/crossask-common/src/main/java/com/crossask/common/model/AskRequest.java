package com.crossask.common.model;

/**
 * /ask 请求体。
 * v0.9：新增 sessionId 可选字段，不传则单轮（向后兼容 v0.7）。
 */
public class AskRequest {

    private String question;
    /** 会话 ID（可选）。传入则启用多轮记忆，不传则单轮。 */
    private String sessionId;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
