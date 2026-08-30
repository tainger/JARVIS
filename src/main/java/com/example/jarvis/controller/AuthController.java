package com.example.jarvis.controller;

import com.example.jarvis.dto.AuthResponse;
import com.example.jarvis.dto.LoginRequest;
import com.example.jarvis.dto.RegisterRequest;
import com.example.jarvis.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户认证控制器
 *
 * 接口列表：
 *   POST /api/auth/register  用户注册（公开）
 *   POST /api/auth/login     用户登录（公开）
 *   GET  /api/auth/me        获取当前登录用户信息（需认证）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserService userService;

	public AuthController(UserService userService) {
		this.userService = userService;
	}

	/**
	 * 用户注册
	 *
	 * @param req 注册信息（用户名、密码、昵称、邮箱）
	 * @return AuthResponse（JWT + 用户基本信息）
	 */
	@PostMapping("/register")
	public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest req) {
		AuthResponse resp = userService.register(req);
		return ResponseEntity.status(HttpStatus.CREATED).body(resp);
	}

	/**
	 * 用户登录
	 *
	 * @param req 登录信息（用户名、密码）
	 * @return AuthResponse（JWT + 用户基本信息）
	 */
	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest req) {
		return ResponseEntity.ok(userService.login(req));
	}

	/**
	 * 获取当前登录用户信息
	 * 用于前端刷新页面后恢复登录态
	 *
	 * @return 当前用户信息
	 */
	@GetMapping("/me")
	public ResponseEntity<Map<String, Object>> getCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		// JWT 过滤器已经校验过 token，这里 authentication 一定不为 null
		if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
					"error", "未登录或登录已过期"
			));
		}

		var user = userService.findById(userId);
		if (user == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
					"error", "用户不存在（可能已被删除）"
			));
		}

		// 注意：不能用 Map.of()，它不允许 null value（昵称/邮箱/头像都可能为空）
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", user.getId());
		body.put("username", user.getUsername());
		body.put("nickname", user.getNickname());     // 允许 null
		body.put("email", user.getEmail());           // 允许 null
		body.put("role", user.getRole());
		body.put("avatarUrl", user.getAvatarUrl());   // 允许 null
		body.put("lastLoginAt", user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString());
		body.put("createdAt", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
		return ResponseEntity.ok(body);
	}

}
