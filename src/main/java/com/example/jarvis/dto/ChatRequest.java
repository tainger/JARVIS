package com.example.jarvis.dto;

/**
 * 聊天请求 DTO。
 *
 * @param message       用户消息内容
 * @param conversationId 会话ID（可选，为空时后端自动创建新会话）
 * @param mode          对话模式（可选，如 "source-analysis" 表示源码分析模式）
 */
public record ChatRequest(String message, String conversationId, String mode) {

	/** 兼容旧调用：只有 message 的场景 */
	public static ChatRequest of(String message) {
		return new ChatRequest(message, null, null);
	}

}
