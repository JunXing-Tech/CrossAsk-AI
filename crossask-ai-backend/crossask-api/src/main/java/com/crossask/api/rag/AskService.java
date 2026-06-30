package com.crossask.api.rag;

import com.crossask.api.chat.ChatHistoryService;
import com.crossask.api.product.ProductQueryTool;
import com.crossask.common.model.AskRequest;
import com.crossask.common.model.AskResponse;
import com.crossask.common.model.ToolCallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v0.9 问答服务（重构自 v0.7）：
 * <p>
 * 在 v0.7 Function Calling 基础上加多轮对话记忆：
 * <ol>
 *   <li>若 sessionId 非空，从 MySQL chat_history 取最近 5 轮历史消息</li>
 *   <li>LLM (qwen-plus) 基于 system + history + user + tools 生成 answer</li>
 *   <li>若 sessionId 非空，把本轮 user + assistant 消息追加到 chat_history</li>
 *   <li>从 {@link ToolCallContext} 取出 sources + products，与 answer 一并装入 AskResponse</li>
 * </ol>
 * 兜底：两个工具收集器都为空时返回 {@link #FALLBACK_EMPTY}；LLM 异常返回 {@link #FALLBACK_ERROR}。
 * sessionId 为空时退化为 v0.7 单轮模式。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private static final String SYSTEM_PROMPT = """
            You are CrossAsk, a cross-border e-commerce customer support assistant.

            You have access to two tools:
            1. searchDocs    - for policy / shipping / return / customs questions (eBay/USPS help center docs)
            2. queryProducts - for product catalog (price / stock / brand / seller / condition)

            CRITICAL Rules:
            - You MUST call the appropriate tool BEFORE answering EVERY question, even in multi-turn conversations.
            - Do NOT answer from memory or conversation history alone. The user's current question may need
              fresh information that requires a tool call. For example:
              * "免邮吗？" / "Is it free shipping?" after a product question -> call queryProducts again with
                the previously discussed product as keyword + freeShippingOnly=true
              * "可以退吗？" / "Can I return it?" -> call searchDocs for return policy
              * "运费怎么算？" -> call searchDocs for shipping info
            - The ONLY exceptions where you skip tool calls are:
              * Greetings ("你好", "hello") - reply briefly without tools
              * Questions completely unrelated to e-commerce (weather, news) - say you cannot help
            - For mixed questions (e.g. "how to return this iPhone"), call BOTH tools.
            - The product catalog titles are stored in ENGLISH. When calling queryProducts,
              translate Chinese product/category keywords to English first (e.g.
              "耳机" -> "headphones", "笔记本电脑" -> "MacBook" or "laptop",
              "手机" -> "iPhone" or relevant model, "电子书阅读器" -> "Kindle",
              "平板" -> "iPad", "手表" -> "Watch").
            - If a tool returns empty, respond honestly that you cannot find the info - do not hallucinate.
            - When the user uses pronouns like "它/这个/那个/its/this/that", refer to the conversation history
              to understand what they are asking about, then call the appropriate tool with the resolved subject.
            - Cite sources at the end:
              - For docs: "Source: <title> (<url>)"
              - For products: "Product: <title> | $<price> | <seller>"
            - Reply in the SAME language as the user's question (Chinese for Chinese, English for English).
            - Keep answers concise and to the point.
            """;

    private static final String FALLBACK_EMPTY = "未找到相关信息，建议联系人工客服。";
    private static final String FALLBACK_ERROR = "服务暂时不可用，请稍后重试。";

    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** 流式问答专用线程池（每个 SSE 请求占一个线程跑同步 call + 切片推送）。 */
    private static final java.util.concurrent.ExecutorService STREAM_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "ask-stream");
                t.setDaemon(true);
                return t;
            });

    public AskService(ChatClient.Builder builder,
                      RagSearchTool ragSearchTool,
                      ProductQueryTool productQueryTool,
                      ChatHistoryService chatHistoryService) {
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(ragSearchTool, productQueryTool)
                .build();
        this.chatHistoryService = chatHistoryService;
    }

    public AskResponse ask(AskRequest request) {
        String question = request.getQuestion();
        String sessionId = request.getSessionId();

        // 入口先重置 ThreadLocal 收集器，避免线程复用串数据
        ToolCallContext.reset();

        // v0.9：读取历史消息（sessionId 为空则跳过）
        List<Message> history = List.of();
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                history = chatHistoryService.getHistory(sessionId);
                log.info("读取历史: sessionId={}, turns={}", sessionId, history.size() / 2);
            } catch (Exception e) {
                log.warn("读取 chat_history 失败，降级为单轮: {}", e.getMessage());
                history = List.of();
            }
        }

        // 构造 LLM 请求
        var spec = chatClient.prompt().user(question);
        if (!history.isEmpty()) {
            spec = spec.messages(history);
        }

        String answer;
        try {
            answer = spec.call().content();
        } catch (Exception e) {
            log.error("LLM/Tool 调用失败", e);
            return new AskResponse(FALLBACK_ERROR, List.of(), List.of());
        }

        // v0.9：持久化历史（失败不中断主流程）
        if (sessionId != null && !sessionId.isBlank() && answer != null && !answer.isBlank()) {
            try {
                chatHistoryService.appendTurn(sessionId, question, answer);
            } catch (Exception e) {
                log.warn("写入 chat_history 失败，不中断主流程: {}", e.getMessage());
            }
        }

        var sources = ToolCallContext.getSources();
        var products = ToolCallContext.getProducts();

        // 两个收集器都空 → 工具都没召回到有用数据，返回兜底
        if (ToolCallContext.isEmpty()) {
            log.info("两个工具都未返回结果，触发 FALLBACK_EMPTY");
            return new AskResponse(FALLBACK_EMPTY, sources, products);
        }

        if (answer == null || answer.isBlank()) {
            answer = FALLBACK_ERROR;
        }

        log.info("问答完成: sessionId={}, question={}, sources={}, products={}, answerLen={}",
                sessionId, question, sources.size(), products.size(), answer.length());
        return new AskResponse(answer, sources, products);
    }

    /**
     * v1.1 流式问答：用 SseEmitter 推送 token / metadata / done / error 事件。
     * <p>
     * 实现策略：在独立线程内同步 call()（工具调用 + ToolCallContext 全在同一线程，可靠），
     * 拿到完整 answer 后按小块切片逐块 emit，模拟打字效果；最后 emit 完整 metadata。
     * <p>
     * 为何不用真·流式 stream()：ToolCallContext 是 ThreadLocal，stream() 的工具调用与
     * onComplete 在不同 Reactor worker 线程，导致 sources/products 取不到。
     * 改用同步 call + 切片推送，牺牲首字延迟换取 metadata（商品/文档卡片）完整可靠。
     */
    public void askStream(AskRequest request, SseEmitter emitter) {
        STREAM_EXECUTOR.execute(() -> {
            String question = request.getQuestion();
            String sessionId = request.getSessionId();

            // 同一线程内：reset -> 读历史 -> call（工具写 ThreadLocal）-> 读 ThreadLocal，全程线程一致
            ToolCallContext.reset();

            List<Message> history = List.of();
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    history = chatHistoryService.getHistory(sessionId);
                    log.info("[stream] 读取历史: sessionId={}, turns={}", sessionId, history.size() / 2);
                } catch (Exception e) {
                    log.warn("[stream] 读取 chat_history 失败，降级单轮: {}", e.getMessage());
                }
            }

            var spec = chatClient.prompt().user(question);
            if (!history.isEmpty()) {
                spec = spec.messages(history);
            }

            String answer;
            try {
                answer = spec.call().content();
            } catch (Exception e) {
                log.error("[stream] LLM 调用失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(escapeForSse(FALLBACK_ERROR)));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
                return;
            }

            var sources = ToolCallContext.getSources();
            var products = ToolCallContext.getProducts();

            // 兜底逻辑与同步 ask() 保持一致：两个工具都没召回 → FALLBACK_EMPTY；answer 为空 → FALLBACK_ERROR
            if (ToolCallContext.isEmpty()) {
                log.info("[stream] 两个工具都未返回结果，触发 FALLBACK_EMPTY");
                answer = FALLBACK_EMPTY;
            } else if (answer == null || answer.isBlank()) {
                answer = FALLBACK_ERROR;
            }

            try {
                // 按 code point 切片推送（每片 ~2 个码点），避免截断 emoji/代理对导致乱码
                int len = answer.length();
                int i = 0;
                while (i < len) {
                    int end = i;
                    int cp = 0;
                    // 每片推进约 2 个 code point，且不在代理对中间断开
                    while (end < len && cp < 2) {
                        end = answer.offsetByCodePoints(end, 1);
                        cp++;
                    }
                    emitter.send(SseEmitter.event().name("token")
                            .data(escapeForSse(answer.substring(i, end))));
                    i = end;
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // metadata：此时 sources/products 来自同线程的 ToolCallContext，可靠
                Map<String, Object> meta = Map.of("sources", sources, "products", products);
                emitter.send(SseEmitter.event().name("metadata")
                        .data(jsonMapper.writeValueAsString(meta)));

                // 先持久化历史，再发 done —— 保证前端收到 done 后 loadSessions 能立即拿到新会话
                if (sessionId != null && !sessionId.isBlank() && !answer.isBlank()) {
                    try {
                        chatHistoryService.appendTurn(sessionId, question, answer);
                    } catch (Exception e) {
                        log.warn("[stream] 写入 chat_history 失败: {}", e.getMessage());
                    }
                }

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

                log.info("[stream] 完成: sessionId={}, sources={}, products={}, answerLen={}",
                        sessionId, sources.size(), products.size(), answer.length());
            } catch (IOException e) {
                log.warn("[stream] 推送失败（客户端可能已断开）: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
    }

    /** SSE data 中含 \n / \r 会破坏帧分隔，需转义。 */
    private static String escapeForSse(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
