package com.crossask.api.chat;

import com.crossask.common.mapper.ChatHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * v0.9 定时清理过期对话历史。
 * 默认每周日凌晨 3 点清理 7 天前的记录。
 */
@Component
public class ChatHistoryCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCleanupTask.class);

    private final ChatHistoryMapper mapper;
    private final ChatMemoryProperties props;

    public ChatHistoryCleanupTask(ChatHistoryMapper mapper, ChatMemoryProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(cron = "${crossask.chat.memory.cleanup-cron:0 0 3 * * SUN}")
    public void cleanup() {
        int deleted = mapper.deleteOlderThan(props.getRetentionDays());
        log.info("ChatHistory 清理完成: 删除 {} 条 {} 天前的记录", deleted, props.getRetentionDays());
    }
}
