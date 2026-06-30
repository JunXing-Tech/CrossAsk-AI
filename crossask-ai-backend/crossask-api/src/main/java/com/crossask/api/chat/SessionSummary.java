package com.crossask.api.chat;

/**
 * v1.1 会话列表项（前端侧边栏用）。
 */
public record SessionSummary(
        String sessionId,
        String title,
        String lastTime,
        int msgCount
) {}
