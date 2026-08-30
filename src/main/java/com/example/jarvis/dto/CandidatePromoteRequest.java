package com.example.jarvis.dto;

import java.util.List;

/**
 * 候选池转正请求：补全标注要素后追加进标注集。
 */
public record CandidatePromoteRequest(
		String type,                      // 用例类型（精确词/近义改写/数字核对/多条件组合）
		String expectDoc,                 // 预期命中文档标题
		List<String> expectChunkKeywords, // 块内容标注关键词（可选）
		List<String> expectAnswerKeywords // 答案要素关键词（可选，空则生成层评测不抽测该条）
) {
}
