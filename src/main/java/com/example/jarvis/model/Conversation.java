package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * 会话实体类，对应数据库表 conversation。
 * 一个用户拥有多个会话，每个会话包含多条消息。
 */
public class Conversation {

	private Long id;

	private Long userId;

	private String title;

	private LocalDateTime lastActiveAt;

	private LocalDateTime createdAt;

	public Conversation() {
	}

	public Conversation(Long userId, String title) {
		this.userId = userId;
		this.title = title;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public LocalDateTime getLastActiveAt() {
		return lastActiveAt;
	}

	public void setLastActiveAt(LocalDateTime lastActiveAt) {
		this.lastActiveAt = lastActiveAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
