package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * 系统用户实体类
 * 对应数据库表 sys_user
 */
public class SysUser {

	/** 用户ID（主键，自增） */
	private Long id;

	/** 登录用户名（唯一） */
	private String username;

	/** BCrypt 加密后的密码哈希 */
	private String passwordHash;

	/** 显示昵称 */
	private String nickname;

	/** 邮箱 */
	private String email;

	/** 角色：ADMIN / USER */
	private String role;

	/** 状态：ACTIVE / DISABLED */
	private String status;

	/** 头像URL */
	private String avatarUrl;

	/** 最近登录时间 */
	private LocalDateTime lastLoginAt;

	/** 创建时间 */
	private LocalDateTime createdAt;

	/** 更新时间 */
	private LocalDateTime updatedAt;

	// --- 构造方法 ---

	public SysUser() {
	}

	public SysUser(String username, String passwordHash, String nickname) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
	}

	// --- Getter / Setter ---

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public void setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
	}

	public LocalDateTime getLastLoginAt() {
		return lastLoginAt;
	}

	public void setLastLoginAt(LocalDateTime lastLoginAt) {
		this.lastLoginAt = lastLoginAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
