package com.crossask.api.chat;

/**
 * v1.1 会话内单条消息（前端加载历史用）。
 */
public record ChatMessageDto(
        String role,
        String content,
        String createdAt
) {}
