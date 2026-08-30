package com.example.jarvis.dto;

/**
 * 回答引用的知识库来源片段（与 prompt 中的 [n] 编号一一对应）。
 */
public record ChatSource(
		int ref,
		long documentId,
		String documentTitle,
		int seq,
		double score,
		String snippet) {
}
