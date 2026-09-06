package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * 消息实体类，对应数据库表 message。
 * 短期记忆的持久化单元，一个会话包含多条消息。
 */
public class Message {

	private Long id;

	private Long conversationId;

	private Long userId;

	/** 角色：user / assistant / system / tool */
	private String role;

	private String content;

	private Integer tokenCount;

	private LocalDateTime createdAt;

	public Message() {
	}

	public Message(Long conversationId, Long userId, String role, String content) {
		this.conversationId = conversationId;
		this.userId = userId;
		this.role = role;
		this.content = content;
		this.tokenCount = 0;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getConversationId() {
		return conversationId;
	}

	public void setConversationId(Long conversationId) {
		this.conversationId = conversationId;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Integer getTokenCount() {
		return tokenCount;
	}

	public void setTokenCount(Integer tokenCount) {
		this.tokenCount = tokenCount;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
