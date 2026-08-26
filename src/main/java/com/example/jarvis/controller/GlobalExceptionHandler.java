package com.example.jarvis.controller;

import java.util.Map;

import io.agentscope.core.model.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, Object>> handleAuthException(AuthenticationException e) {
		log.warn("Model authentication error: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
				"error", "模型服务认证失败",
				"detail", "请检查 AGENTSCOPE_API_KEY / AGENTSCOPE_BASE_URL / AGENTSCOPE_MODEL 环境变量配置",
				"hint", "当前占位符 sk-demo-key 无效，请替换为真实的 API Key"
		));
	}

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
