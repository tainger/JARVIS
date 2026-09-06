package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * 用户长期记忆实体类，对应数据库表 user_memory。
 * 跨会话的用户画像事实/偏好/目标，通过 bge-m3 向量化后支持语义检索。
 */
public class UserMemory {

	private Long id;

	private Long userId;

	/** 记忆内容（文本描述） */
	private String content;

	/** 类型：fact=事实, preference=偏好, goal=目标 */
	private String type;

	/** JSON float 数组形式存储的向量；null 表示未成功向量化 */
	private String embedding;

	private Integer dim;

	private LocalDateTime createdAt;

	private LocalDateTime lastAccessedAt;

	/** 软删除/过时标记：1=有效, 0=已删除 */
	private Integer isActive;

	public UserMemory() {
	}

	public UserMemory(Long userId, String content, String type) {
		this.userId = userId;
		this.content = content;
		this.type = type;
		this.dim = 1024;
		this.isActive = 1;
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

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getEmbedding() {
		return embedding;
	}

	public void setEmbedding(String embedding) {
		this.embedding = embedding;
	}

	public Integer getDim() {
		return dim;
	}

	public void setDim(Integer dim) {
		this.dim = dim;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getLastAccessedAt() {
		return lastAccessedAt;
	}

	public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
		this.lastAccessedAt = lastAccessedAt;
	}

	public Integer getIsActive() {
		return isActive;
	}

	public void setIsActive(Integer isActive) {
		this.isActive = isActive;
	}

}
