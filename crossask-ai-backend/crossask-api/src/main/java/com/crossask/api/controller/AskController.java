package com.crossask.api.controller;

import com.crossask.common.model.AskRequest;
import com.crossask.common.model.AskResponse;
import com.crossask.api.rag.AskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        // v1.0 修复 1：参数校验，空问题返回 400 而非 500
        if (request == null
                || request.getQuestion() == null
                || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "question 不能为空");
        }
        return askService.ask(request);
    }

    /**
     * v1.1 流式问答：SSE 输出 token/metadata/done/error 事件。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskRequest request) {
        if (request == null
                || request.getQuestion() == null
                || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "question 不能为空");
        }
        // 超时 2 分钟（LLM 流式可能较慢）
        SseEmitter emitter = new SseEmitter(120_000L);
        askService.askStream(request, emitter);
        return emitter;
    }
}
