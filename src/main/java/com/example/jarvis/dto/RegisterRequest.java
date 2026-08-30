package com.example.jarvis.dto;

/**
 * 注册请求 DTO
 */
public record RegisterRequest(
		String username,  // 用户名（必填，3-32字符）
		String password,  // 明文密码（必填，6-64字符）
		String nickname,  // 昵称（可选）
		String email      // 邮箱（可选）
) {
}
