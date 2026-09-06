package com.example.jarvis.model;

import java.time.LocalDateTime;

public class DebugBreakpoint {

	private Long id;
	private Long userId;
	private Long conversationId;
	private Long messageId;
	private int stepIndex;
	private String note;
	private LocalDateTime createdAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public Long getConversationId() { return conversationId; }
	public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
	public Long getMessageId() { return messageId; }
	public void setMessageId(Long messageId) { this.messageId = messageId; }
	public int getStepIndex() { return stepIndex; }
	public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
	public String getNote() { return note; }
	public void setNote(String note) { this.note = note; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
