package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话数据访问层
 */
@Mapper
public interface ConversationMapper {

	/**
	 * 查询用户的会话列表（按最近活跃时间倒序）
	 */
	List<Conversation> findByUserId(@Param("userId") Long userId);

	/**
	 * 根据ID查询会话
	 */
	Conversation findById(@Param("id") Long id);

	/**
	 * 插入新会话
	 */
	int insert(Conversation conversation);

	/**
	 * 更新会话标题
	 */
	int updateTitle(@Param("id") Long id, @Param("title") String title);

	/**
	 * 更新最近活跃时间
	 */
	int updateLastActiveAt(@Param("id") Long id);

	/**
	 * 删除会话（物理删除，消息表通过应用层级联删除）
	 */
	int deleteById(@Param("id") Long id);

	/**
	 * 校验会话是否属于指定用户（多租户隔离）
	 */
	int countByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

}
