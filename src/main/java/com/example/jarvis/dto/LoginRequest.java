package com.example.jarvis.dto;

/**
 * 登录请求 DTO
 */
public record LoginRequest(
		String username,  // 用户名
		String password   // 明文密码
) {
}
