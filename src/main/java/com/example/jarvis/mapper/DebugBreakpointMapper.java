package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.DebugBreakpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DebugBreakpointMapper {

	List<DebugBreakpoint> findByUserAndConversation(@Param("userId") Long userId,
			@Param("conversationId") Long conversationId);

	int insert(DebugBreakpoint breakpoint);

	int delete(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
			@Param("messageId") Long messageId, @Param("stepIndex") int stepIndex);

	int exists(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
			@Param("messageId") Long messageId, @Param("stepIndex") int stepIndex);
}
