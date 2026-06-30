package com.crossask.api.controller;

import com.crossask.api.chat.ChatHistoryService;
import com.crossask.api.chat.ChatMessageDto;
import com.crossask.api.chat.SessionSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * v1.1 会话历史接口（前端侧边栏多会话列表 / 切换 / 删除）。
 */
@RestController
public class SessionController {

    private final ChatHistoryService chatHistoryService;

    public SessionController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    /** 会话列表（最近活跃倒序）。 */
    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(@RequestParam(defaultValue = "50") int limit) {
        return chatHistoryService.listSessions(limit);
    }

    /** 某会话的全部消息（正序）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageDto> getMessages(@PathVariable String sessionId) {
        return chatHistoryService.getMessages(sessionId);
    }

    /** 删除某会话。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        boolean ok = chatHistoryService.deleteSession(sessionId);
        return Map.of("success", ok);
    }
}
