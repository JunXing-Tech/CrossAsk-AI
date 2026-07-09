package com.crossask.api.chat;

import com.crossask.common.mapper.ChatHistoryMapper;
import com.crossask.common.model.ChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v0.9 对话历史服务（自定义，不实现 Spring AI ChatMemoryRepository 接口）。
 * <p>
 * 用 MyBatis-Plus 操作 chat_history 表，返回 {@link Message} 列表供 AskService 拼接到 ChatClient。
 * 只存 user + assistant 的 text，不存 tool_call 中间消息。
 * v1.2 所有操作增加 clientId 参数，实现浏览器级会话隔离。
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private final ChatHistoryMapper mapper;
    private final ChatMemoryProperties props;

    public ChatHistoryService(ChatHistoryMapper mapper, ChatMemoryProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 取最近 {@code turns} 轮（turns*2 条 user+assistant 消息），按时间正序返回。
     * LLM 需要正序看到对话历史。
     */
    public List<Message> getHistory(String sessionId, String clientId) {
        try {
            int limit = props.getTurns() * 2;
            List<ChatHistory> rows = mapper.selectRecentDesc(sessionId, clientId, limit);
            if (rows.isEmpty()) {
                return List.of();
            }
            // selectRecentDesc 按 id DESC 取，需反转为正序
            Collections.reverse(rows);

            List<Message> messages = new ArrayList<>(rows.size());
            for (ChatHistory row : rows) {
                if ("user".equals(row.getRole())) {
                    messages.add(new UserMessage(row.getContent()));
                } else if ("assistant".equals(row.getRole())) {
                    messages.add(new AssistantMessage(row.getContent()));
                }
            }
            log.debug("getHistory sessionId={} -> {} 条消息", sessionId, messages.size());
            return messages;
        } catch (Exception e) {
            log.warn("读取 chat_history 失败，降级为单轮: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 追加本轮 user + assistant 消息。
     * 失败不抛异常（调用方已 try-catch），但此处也兜一层。
     */
    public void appendTurn(String sessionId, String clientId, String userQuestion, String assistantAnswer) {
        try {
            int nextTurn = mapper.maxTurnIndex(sessionId, clientId) + 1;

            ChatHistory userRow = new ChatHistory();
            userRow.setSessionId(sessionId);
            userRow.setClientId(clientId);
            userRow.setTurnIndex(nextTurn);
            userRow.setRole("user");
            userRow.setContent(userQuestion);
            mapper.insert(userRow);

            ChatHistory assistantRow = new ChatHistory();
            assistantRow.setSessionId(sessionId);
            assistantRow.setClientId(clientId);
            assistantRow.setTurnIndex(nextTurn);
            assistantRow.setRole("assistant");
            assistantRow.setContent(assistantAnswer);
            mapper.insert(assistantRow);

            log.debug("appendTurn sessionId={} turn={} -> 2 条消息已写入", sessionId, nextTurn);
        } catch (Exception e) {
            log.warn("写入 chat_history 失败，不中断主流程: {}", e.getMessage());
        }
    }

    /** v1.1 会话列表（最近活跃倒序），用于前端侧边栏。v1.2 按 clientId 隔离。 */
    public List<SessionSummary> listSessions(int limit, String clientId) {
        try {
            List<java.util.Map<String, Object>> rows = mapper.selectSessions(limit, clientId);
            List<SessionSummary> result = new ArrayList<>(rows.size());
            for (var row : rows) {
                String title = row.get("title") == null ? "新对话" : String.valueOf(row.get("title"));
                // 标题过长截断
                if (title.length() > 30) {
                    title = title.substring(0, 30) + "…";
                }
                result.add(new SessionSummary(
                        String.valueOf(row.get("sessionId")),
                        title,
                        String.valueOf(row.get("lastTime")),
                        row.get("msgCount") == null ? 0 : ((Number) row.get("msgCount")).intValue()
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("查询会话列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** v1.1 拉取某会话全部消息（正序），用于前端切换会话时还原对话。v1.2 加 clientId 校验。 */
    public List<ChatMessageDto> getMessages(String sessionId, String clientId) {
        try {
            List<ChatHistory> rows = mapper.selectAllBySession(sessionId, clientId);
            List<ChatMessageDto> result = new ArrayList<>(rows.size());
            for (ChatHistory row : rows) {
                result.add(new ChatMessageDto(
                        row.getRole(),
                        row.getContent(),
                        row.getCreatedAt() == null ? null : row.getCreatedAt().toString()
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("查询会话消息失败 sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** v1.1 删除某会话。v1.2 加 clientId 校验防止越权删除。 */
    public boolean deleteSession(String sessionId, String clientId) {
        try {
            mapper.deleteBySession(sessionId, clientId);
            return true;
        } catch (Exception e) {
            log.warn("删除会话失败 sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }
}
