package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * Agent 推理轨迹实体，对应 agent_trace 表。
 * 每轮 ReAct 迭代记录一行，通过 messageId 关联到 message 表。
 */
public class AgentTrace {

	private Long id;
	private Long messageId;
	private Long conversationId;
	private Long userId;
	private Integer stepIndex;
	/** reasoning / tool_call / tool_result / summary */
	private String stepType;
	private String content;
	private String toolName;
	private String toolArgs;
	private String toolResult;
	private Integer durationMs;
	private Boolean isTruncated;
	private LocalDateTime createdAt;

	public AgentTrace() {
	}

	@Override
	public String toString() {
		return "AgentTrace{step=%d, type=%s, tool=%s, duration=%sms}".formatted(
				stepIndex, stepType, toolName, durationMs);
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }

	public Long getMessageId() { return messageId; }
	public void setMessageId(Long messageId) { this.messageId = messageId; }

	public Long getConversationId() { return conversationId; }
	public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }

	public Integer getStepIndex() { return stepIndex; }
	public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }

	public String getStepType() { return stepType; }
	public void setStepType(String stepType) { this.stepType = stepType; }

	public String getContent() { return content; }
	public void setContent(String content) { this.content = content; }

	public String getToolName() { return toolName; }
	public void setToolName(String toolName) { this.toolName = toolName; }

	public String getToolArgs() { return toolArgs; }
	public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }

	public String getToolResult() { return toolResult; }
	public void setToolResult(String toolResult) { this.toolResult = toolResult; }

	public Integer getDurationMs() { return durationMs; }
	public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }

	public Boolean getIsTruncated() { return isTruncated; }
	public void setIsTruncated(Boolean isTruncated) { this.isTruncated = isTruncated; }

	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
