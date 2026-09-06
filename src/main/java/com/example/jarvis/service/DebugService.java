package com.example.jarvis.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.example.jarvis.config.AgentScopeConfig;
import com.example.jarvis.mapper.AgentTraceMapper;
import com.example.jarvis.mapper.ConversationMapper;
import com.example.jarvis.mapper.DebugBreakpointMapper;
import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.model.AgentTrace;
import com.example.jarvis.model.Conversation;
import com.example.jarvis.model.DebugBreakpoint;
import com.example.jarvis.model.Message;

@Service
public class DebugService {

	private static final Logger log = LoggerFactory.getLogger(DebugService.class);

	private final AgentTraceMapper traceMapper;
	private final MessageMapper messageMapper;
	private final ConversationMapper conversationMapper;
	private final DebugBreakpointMapper breakpointMapper;
	private final AgentScopeConfig agentScopeConfig;

	public DebugService(AgentTraceMapper traceMapper, MessageMapper messageMapper,
			ConversationMapper conversationMapper, DebugBreakpointMapper breakpointMapper,
			AgentScopeConfig agentScopeConfig) {
		this.traceMapper = traceMapper;
		this.messageMapper = messageMapper;
		this.conversationMapper = conversationMapper;
		this.breakpointMapper = breakpointMapper;
		this.agentScopeConfig = agentScopeConfig;
	}

	/**
	 * 校验会话归属当前用户，不属于则抛 AccessDeniedException
	 */
	private void checkOwnership(Long conversationId, Long userId) {
		if (conversationMapper.countByIdAndUserId(conversationId, userId) == 0) {
			throw new AccessDeniedException("无权访问该会话");
		}
	}

	/**
	 * 获取对话的调试步骤列表（按 assistant 消息分组）
	 */
	public Map<String, Object> getSteps(Long conversationId, Long userId) {
		checkOwnership(conversationId, userId);

		Map<String, Object> result = new LinkedHashMap<>();
		Conversation conv = conversationMapper.findById(conversationId);
		result.put("conversation", Map.of(
				"id", conversationId,
				"title", conv != null ? conv.getTitle() : ""));

		List<Message> messages = messageMapper.findByConversationId(conversationId, null);
		List<AgentTrace> traces = traceMapper.findByConversationId(conversationId, userId);

		Map<Long, List<AgentTrace>> tracesByMsg = new LinkedHashMap<>();
		for (AgentTrace t : traces) {
			tracesByMsg.computeIfAbsent(t.getMessageId(), k -> new ArrayList<>()).add(t);
		}

		List<Map<String, Object>> msgList = new ArrayList<>();
		for (Message msg : messages) {
			if (!"assistant".equals(msg.getRole())) continue;
			List<AgentTrace> msgTraces = tracesByMsg.getOrDefault(msg.getId(), List.of());
			List<Map<String, Object>> steps = new ArrayList<>();
			for (AgentTrace t : msgTraces) {
				steps.add(traceToMap(t));
			}
			Map<String, Object> msgMap = new LinkedHashMap<>();
			msgMap.put("messageId", msg.getId());
			msgMap.put("role", msg.getRole());
			msgMap.put("content", truncate(msg.getContent(), 200));
			msgMap.put("stepCount", msgTraces.size());
			msgMap.put("steps", steps);
			msgList.add(msgMap);
		}
		result.put("messages", msgList);
		return result;
	}

	/**
	 * 重建某步骤的完整上下文
	 */
	public Map<String, Object> buildContext(Long conversationId, Long messageId, int stepIndex, Long userId) {
		checkOwnership(conversationId, userId);

		Map<String, Object> context = new LinkedHashMap<>();

		// 1. System Prompt
		context.put("systemPrompt", agentScopeConfig.getAgent().getSysPrompt());

		// 2. 历史消息（该 assistant 消息之前的所有消息）
		List<Message> allMessages = messageMapper.findByConversationId(conversationId, null);
		List<Map<String, Object>> priorMessages = new ArrayList<>();
		boolean found = false;
		for (Message m : allMessages) {
			if (m.getId().equals(messageId)) {
				found = true;
				break;
			}
			priorMessages.add(Map.of(
					"role", m.getRole(),
					"content", truncate(m.getContent(), 300)));
		}
		if (!found) {
			log.warn("消息 {} 在对话 {} 中未找到", messageId, conversationId);
		}
		context.put("priorMessages", priorMessages);

		// 3. 该消息的所有 trace
		List<AgentTrace> allTraces = traceMapper.findByConversationId(conversationId, userId).stream()
				.filter(t -> t.getMessageId().equals(messageId))
				.sorted((a, b) -> Integer.compare(a.getStepIndex(), b.getStepIndex()))
				.toList();

		// 4. 步骤 1..N-1 的累积结果
		List<Map<String, Object>> priorTraces = new ArrayList<>();
		Map<String, Object> currentStep = null;
		for (AgentTrace t : allTraces) {
			if (t.getStepIndex() < stepIndex) {
				priorTraces.add(traceToMap(t));
			} else if (t.getStepIndex() == stepIndex) {
				currentStep = traceToMap(t);
			}
		}
		context.put("priorTraces", priorTraces);
		context.put("currentStep", currentStep);

		// 5. 断点信息
		List<DebugBreakpoint> bps = breakpointMapper.findByUserAndConversation(userId, conversationId);
		DebugBreakpoint matchingBp = bps.stream()
				.filter(b -> b.getMessageId().equals(messageId) && b.getStepIndex() == stepIndex)
				.findFirst().orElse(null);
		Map<String, Object> bpInfo = new LinkedHashMap<>();
		bpInfo.put("hasBreakpoint", matchingBp != null);
		bpInfo.put("note", matchingBp != null ? matchingBp.getNote() : null);
		context.put("breakpoint", bpInfo);

		return context;
	}

	/**
	 * 添加断点（幂等）
	 */
	public void addBreakpoint(Long userId, Long conversationId, Long messageId, int stepIndex, String note) {
		checkOwnership(conversationId, userId);

		DebugBreakpoint bp = new DebugBreakpoint();
		bp.setUserId(userId);
		bp.setConversationId(conversationId);
		bp.setMessageId(messageId);
		bp.setStepIndex(stepIndex);
		bp.setNote(note);
		breakpointMapper.insert(bp);
	}

	/**
	 * 删除断点
	 */
	public void removeBreakpoint(Long userId, Long conversationId, Long messageId, int stepIndex) {
		checkOwnership(conversationId, userId);

		breakpointMapper.delete(userId, conversationId, messageId, stepIndex);
	}

	/**
	 * 获取对话的所有断点
	 */
	public List<DebugBreakpoint> getBreakpoints(Long userId, Long conversationId) {
		checkOwnership(conversationId, userId);

		return breakpointMapper.findByUserAndConversation(userId, conversationId);
	}

	/**
	 * 对比两个对话的推理路径
	 */
	public Map<String, Object> diffConversations(Long convA, Long convB, Long userId) {
		checkOwnership(convA, userId);
		checkOwnership(convB, userId);

		Map<String, Object> result = new LinkedHashMap<>();

		Conversation ca = conversationMapper.findById(convA);
		Conversation cb = conversationMapper.findById(convB);
		result.put("conversationA", Map.of("id", convA, "title", ca != null ? ca.getTitle() : ""));
		result.put("conversationB", Map.of("id", convB, "title", cb != null ? cb.getTitle() : ""));

		List<AgentTrace> tracesA = getLastAssistantTraces(convA, userId);
		List<AgentTrace> tracesB = getLastAssistantTraces(convB, userId);

		int maxSteps = Math.max(tracesA.size(), tracesB.size());
		List<Map<String, Object>> steps = new ArrayList<>();
		int forkStep = -1;

		for (int i = 0; i < maxSteps; i++) {
			AgentTrace a = i < tracesA.size() ? tracesA.get(i) : null;
			AgentTrace b = i < tracesB.size() ? tracesB.get(i) : null;
			int stepIndex = i + 1;

			String typeA = a != null ? a.getStepType() : null;
			String typeB = b != null ? b.getStepType() : null;
			String toolA = a != null ? a.getToolName() : null;
			String toolB = b != null ? b.getToolName() : null;
			String argsA = a != null ? a.getToolArgs() : null;
			String argsB = b != null ? b.getToolArgs() : null;

			boolean isSame = (typeA == null ? typeB == null : typeA.equals(typeB))
					&& (toolA == null ? toolB == null : toolA.equals(toolB))
					&& (argsA == null ? argsB == null : argsA.equals(argsB));

			if (!isSame && forkStep == -1) {
				forkStep = stepIndex;
			}

			Map<String, Object> stepMap = new LinkedHashMap<>();
			stepMap.put("stepIndex", stepIndex);
			stepMap.put("typeA", a != null ? a.getStepType() : null);
			stepMap.put("typeB", b != null ? b.getStepType() : null);
			stepMap.put("toolA", toolA);
			stepMap.put("toolB", toolB);
			stepMap.put("argsA", truncate(argsA, 200));
			stepMap.put("argsB", truncate(argsB, 200));
			stepMap.put("contentA", truncate(a != null ? a.getContent() : null, 200));
			stepMap.put("contentB", truncate(b != null ? b.getContent() : null, 200));
			stepMap.put("isSame", isSame);
			steps.add(stepMap);
		}

		result.put("forkStep", forkStep);
		result.put("steps", steps);
		return result;
	}

	private List<AgentTrace> getLastAssistantTraces(Long conversationId, Long userId) {
		List<AgentTrace> allTraces = traceMapper.findByConversationId(conversationId, userId);
		if (allTraces.isEmpty()) return List.of();

		Long lastMsgId = allTraces.get(allTraces.size() - 1).getMessageId();
		return allTraces.stream()
				.filter(t -> t.getMessageId().equals(lastMsgId))
				.sorted((a, b) -> Integer.compare(a.getStepIndex(), b.getStepIndex()))
				.toList();
	}

	private Map<String, Object> traceToMap(AgentTrace t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("stepIndex", t.getStepIndex());
		m.put("stepType", t.getStepType());
		m.put("content", t.getContent());
		m.put("toolName", t.getToolName());
		m.put("toolArgs", t.getToolArgs());
		m.put("toolResult", t.getToolResult());
		m.put("durationMs", t.getDurationMs());
		m.put("isTruncated", t.getIsTruncated());
		return m;
	}

	private String truncate(String text, int max) {
		if (text == null) return null;
		return text.length() > max ? text.substring(0, max) + "…" : text;
	}
}
