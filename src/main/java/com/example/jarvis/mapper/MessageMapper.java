package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消息数据访问层（短期记忆持久化）
 */
@Mapper
public interface MessageMapper {

	/**
	 * 查询会话的历史消息（按时间正序），支持限制条数（取最近 N 条）
	 */
	List<Message> findByConversationId(@Param("conversationId") Long conversationId,
			@Param("limit") Integer limit);

	/**
	 * 分页查询会话消息（向上加载更多）
	 */
	List<Message> findByConversationIdPaginated(@Param("conversationId") Long conversationId,
			@Param("offset") int offset, @Param("limit") int limit);

	/**
	 * 插入新消息
	 */
	int insert(Message message);

	/**
	 * 统计会话消息数
	 */
	int countByConversationId(@Param("conversationId") Long conversationId);

	/**
	 * 删除会话的所有消息（级联删除）
	 */
	int deleteByConversationId(@Param("conversationId") Long conversationId);

}
