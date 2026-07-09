package com.crossask.api.controller;

import com.crossask.api.chat.ChatHistoryService;
import com.crossask.api.chat.ChatMessageDto;
import com.crossask.api.chat.SessionSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * v1.1 会话历史接口（前端侧边栏多会话列表 / 切换 / 删除）。
 * v1.2 所有接口增加 X-Client-Id 请求头，实现浏览器级会话隔离。
 */
@RestController
public class SessionController {

    private final ChatHistoryService chatHistoryService;

    public SessionController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    /** 会话列表（最近活跃倒序，按 clientId 隔离）。 */
    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(@RequestParam(defaultValue = "50") int limit,
                                             @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        return chatHistoryService.listSessions(limit, clientId);
    }

    /** 某会话的全部消息（正序，需 clientId 校验）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageDto> getMessages(@PathVariable String sessionId,
                                            @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        return chatHistoryService.getMessages(sessionId, clientId);
    }

    /** 删除某会话（需 clientId 校验防止越权）。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId,
                                             @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        boolean ok = chatHistoryService.deleteSession(sessionId, clientId);
        return Map.of("success", ok);
    }
}
