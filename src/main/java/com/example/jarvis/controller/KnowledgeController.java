package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import com.example.jarvis.model.KnowledgeDocument;
import com.example.jarvis.rag.KnowledgeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口：文档导入（文本/文件内容）、列表、详情、删除、检索测试。
 * 前端上传 .md/.txt 文件时先读为文本，再以 JSON 提交，后端不处理 multipart。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

	private final KnowledgeService knowledgeService;

	public KnowledgeController(KnowledgeService knowledgeService) {
		this.knowledgeService = knowledgeService;
	}

	/** 文档列表（不含正文，避免大响应） */
	@GetMapping("/documents")
	public List<Map<String, Object>> listDocuments() {
		return knowledgeService.listDocuments().stream()
				.map(this::toSummary)
				.toList();
	}

	@GetMapping("/documents/{id}")
	public KnowledgeDocument getDocument(@PathVariable Long id) {
		return knowledgeService.getDocument(id);
	}

	/**
	 * 导入文档。body: {title?, fileName?, content}
	 * content 为纯文本或 Markdown，导入时自动分块并向量化。
	 */
	@PostMapping("/documents")
	public KnowledgeDocument importDocument(@RequestBody Map<String, String> body) {
		String content = body.get("content");
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("文档内容不能为空");
		}
		if (content.length() > 2_000_000) {
			throw new IllegalArgumentException("文档过大（上限约 2MB 文本），请拆分后导入");
		}
		return knowledgeService.importDocument(
				body.get("title"), body.get("fileName"), content);
	}

	@DeleteMapping("/documents/{id}")
	public Map<String, Object> deleteDocument(@PathVariable Long id) {
		knowledgeService.deleteDocument(id);
		return Map.of("deleted", id);
	}

	/** 检索测试。body: {query, topK?} */
	@PostMapping("/search")
	public Map<String, Object> search(@RequestBody Map<String, Object> body) {
		String query = (String) body.get("query");
		Integer topK = body.get("topK") instanceof Number n ? n.intValue() : null;
		List<KnowledgeService.SearchHit> hits = knowledgeService.search(query, topK);
		return Map.of("query", query == null ? "" : query, "hits", hits);
	}

	/** 知识库统计 */
	@GetMapping("/stats")
	public Map<String, Object> stats() {
		return knowledgeService.stats();
	}

	private Map<String, Object> toSummary(KnowledgeDocument doc) {
		return Map.of(
				"id", doc.getId(),
				"title", doc.getTitle(),
				"fileName", doc.getFileName() == null ? "" : doc.getFileName(),
				"chunkCount", doc.getChunkCount(),
				"contentLength", doc.getContent() == null ? 0 : doc.getContent().length(),
				"createdAt", doc.getCreatedAt() == null ? "" : doc.getCreatedAt().toString());
	}

}
