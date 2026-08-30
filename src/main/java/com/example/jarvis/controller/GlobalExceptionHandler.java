package com.example.jarvis.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 注：两个 AuthenticationException 类名冲突，全部使用全限定名避免 import 冲突
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// ======================================================================
	// 1. 参数校验/业务逻辑异常（登录注册、参数错误等） → 400
	// ======================================================================
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("Business validation error: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
				"error", "请求参数错误",
				"detail", e.getMessage()
		));
	}

	// ======================================================================
	// 2. Spring Security 异常
	// ======================================================================

	/** 未登录或 Token 无效 → 401 */
	@ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
	public ResponseEntity<Map<String, Object>> handleSpringAuthException(
			org.springframework.security.core.AuthenticationException e) {
		log.warn("Spring authentication failed: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
				"error", "未登录或登录已过期",
				"detail", "请重新登录"
		));
	}

	/** 已登录但权限不足 → 403 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
		log.warn("Access denied: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
				"error", "权限不足",
				"detail", "当前用户无权执行此操作"
		));
	}

	// ======================================================================
	// 3. 模型服务认证异常（AgentScope 401）
	// ======================================================================
	@ExceptionHandler(io.agentscope.core.model.exception.AuthenticationException.class)
	public ResponseEntity<Map<String, Object>> handleModelAuthException(
			io.agentscope.core.model.exception.AuthenticationException e) {
		log.warn("Model authentication error: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
				"error", "模型服务认证失败",
				"detail", "请检查 AGENTSCOPE_API_KEY / AGENTSCOPE_BASE_URL / AGENTSCOPE_MODEL 环境变量配置",
				"hint", "当前占位符 sk-demo-key 无效，请替换为真实的 API Key"
		));
	}

	// ======================================================================
	// 4. 兜底：其他运行时异常 → 500
	// ======================================================================
	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
		log.error("Unhandled runtime exception", e);
		String msg = e.getMessage();
		if (msg != null && msg.contains("401")) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
					"error", "模型服务认证失败（401）",
					"detail", "请检查 API Key 是否正确"
			));
		}
		if (msg != null && msg.contains("429")) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
					"error", "模型服务限流（429）",
					"detail", "请求过于频繁，请稍后再试"
			));
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
				"error", "服务内部错误",
				"detail", msg != null ? msg.split("\\|")[0].trim() : e.getClass().getSimpleName()
		));
	}

}
