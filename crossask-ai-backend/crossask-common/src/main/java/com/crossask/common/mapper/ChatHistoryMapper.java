package com.crossask.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crossask.common.model.ChatHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * v0.9 对话历史 Mapper（v1.1 增强：会话列表 / 全量消息查询）。
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /** 取最近 limit 条消息（按 id 降序取，再正序返回给 LLM）。 */
    @Select("SELECT * FROM chat_history WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<ChatHistory> selectRecentDesc(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /** 统计当前会话的消息数。 */
    @Select("SELECT COUNT(*) FROM chat_history WHERE session_id = #{sessionId}")
    int countBySession(@Param("sessionId") String sessionId);

    /** 取当前会话最大 turn_index（无记录返回 0）。用于原子计算下一轮序号。 */
    @Select("SELECT COALESCE(MAX(turn_index), 0) FROM chat_history WHERE session_id = #{sessionId}")
    int maxTurnIndex(@Param("sessionId") String sessionId);

    /** 清理 N 天前的历史（定时任务用）。 */
    @Delete("DELETE FROM chat_history WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("days") int days);

    /**
     * v1.1 会话列表：每个 session 一行，取首条 user 消息作标题、最后活跃时间、消息数。
     * 按最近活跃倒序。
     */
    @Select("""
            SELECT t.session_id AS sessionId,
                   t.last_time  AS lastTime,
                   t.msg_count  AS msgCount,
                   (SELECT c2.content FROM chat_history c2
                     WHERE c2.session_id = t.session_id AND c2.role = 'user'
                     ORDER BY c2.id ASC LIMIT 1) AS title
            FROM (
              SELECT session_id,
                     MAX(created_at) AS last_time,
                     COUNT(*)        AS msg_count
              FROM chat_history
              GROUP BY session_id
            ) t
            ORDER BY t.last_time DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectSessions(@Param("limit") int limit);

    /** v1.1 拉取某会话全部消息，按时间正序。 */
    @Select("SELECT * FROM chat_history WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatHistory> selectAllBySession(@Param("sessionId") String sessionId);

    /** v1.1 删除某会话全部消息。 */
    @Delete("DELETE FROM chat_history WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);
}
