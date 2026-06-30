package com.crossask.api.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * v0.9 对话记忆配置（crossask.chat.memory.*）。
 */
@ConfigurationProperties(prefix = "crossask.chat.memory")
public class ChatMemoryProperties {

    /** 保留最近几轮（1 轮 = 1 user + 1 assistant）。 */
    private int turns = 5;

    /** 历史保留天数。 */
    private int retentionDays = 7;

    /** 清理 cron 表达式（默认每周日凌晨 3 点）。 */
    private String cleanupCron = "0 0 3 * * SUN";

    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
