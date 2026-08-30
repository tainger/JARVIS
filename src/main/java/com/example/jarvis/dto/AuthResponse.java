package com.example.jarvis.dto;

import com.example.jarvis.model.SysUser;

/**
 * 登录/注册 成功响应 DTO
 * 返回 token + 用户基本信息（不含密码）
 */
public record AuthResponse(
		String token,        // JWT access token
		String tokenType,    // token 类型：Bearer
		Long   expiresIn,    // 过期时间（秒）
		UserVO user          // 用户信息
) {

	/** 用户信息视图对象（不含密码哈希） */
	public record UserVO(
			Long   id,
			String username,
			String nickname,
			String email,
			String role,
			String avatarUrl
	) {
		public static UserVO from(SysUser u) {
			return new UserVO(
					u.getId(),
					u.getUsername(),
					u.getNickname(),
					u.getEmail(),
					u.getRole(),
					u.getAvatarUrl()
			);
		}
	}

}
