package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.AgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Agent 推理轨迹数据访问层
 */
@Mapper
public interface AgentTraceMapper {

	/** 批量插入 trace 记录 */
	int batchInsert(@Param("list") List<AgentTrace> list);

	/** 按会话ID查询所有 trace（按 message_id + step_index 排序） */
	List<AgentTrace> findByConversationId(@Param("conversationId") Long conversationId,
			@Param("userId") Long userId);

}
