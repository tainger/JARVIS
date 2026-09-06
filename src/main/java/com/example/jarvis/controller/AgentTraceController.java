package com.example.jarvis.controller;

import java.util.List;

import com.example.jarvis.mapper.AgentTraceMapper;
import com.example.jarvis.model.AgentTrace;
import com.example.jarvis.model.Conversation;
import com.example.jarvis.service.ConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/agent")
public class AgentTraceController {

	private final AgentTraceMapper agentTraceMapper;

	private final ConversationService conversationService;

	public AgentTraceController(AgentTraceMapper agentTraceMapper, ConversationService conversationService) {
		this.agentTraceMapper = agentTraceMapper;
		this.conversationService = conversationService;
	}

	@GetMapping("/traces/{conversationId}")
	public List<AgentTrace> getTraces(@PathVariable Long conversationId) {
		Long userId = currentUserId();
		// 校验会话归属
		Conversation conversation = conversationService.getByIdForUser(conversationId, userId);
		if (conversation == null) {
			return List.of();
		}
		return agentTraceMapper.findByConversationId(conversationId, userId);
	}

	private Long currentUserId() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof Long id) {
			return id;
		}
		if (principal instanceof Integer id) {
			return id.longValue();
		}
		throw new IllegalStateException("无法获取当前用户身份，请重新登录");
	}
}
