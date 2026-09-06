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
 * 知识库管理接口：文档导入（异步）、列表、详情、删除、检索测试、导入状态查询。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

	private final KnowledgeService knowledgeService;

	public KnowledgeController(KnowledgeService knowledgeService) {
		this.knowledgeService = knowledgeService;
	}

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
	 * 提交导入文档（异步）。body: {title?, fileName?, content}
	 * 立即返回文档 ID 和 processing 状态，前端轮询 /documents/{id}/status 获取进度。
	 */
	@PostMapping("/documents")
	public Map<String, Object> importDocument(@RequestBody Map<String, String> body) {
		String content = body.get("content");
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("文档内容不能为空");
		}
		if (content.length() > 2_000_000) {
			throw new IllegalArgumentException("文档过大（上限约 2MB 文本），请拆分后导入");
		}
		KnowledgeDocument doc = knowledgeService.submitImport(
				body.get("title"), body.get("fileName"), content);
		return Map.of(
				"id", doc.getId(),
				"title", doc.getTitle(),
				"status", doc.getStatus(),
				"chunkTotal", doc.getChunkTotal());
	}

	@GetMapping("/documents/{id}/status")
	public Map<String, Object> getImportStatus(@PathVariable Long id) {
		KnowledgeDocument doc = knowledgeService.getImportStatus(id);
		return Map.of(
				"id", doc.getId(),
				"status", doc.getStatus() == null ? "ready" : doc.getStatus(),
				"chunkProgress", doc.getChunkProgress(),
				"chunkTotal", doc.getChunkTotal(),
				"errorMessage", doc.getErrorMessage() == null ? "" : doc.getErrorMessage());
	}

	@PostMapping("/documents/{id}/retry")
	public Map<String, Object> retryImport(@PathVariable Long id) {
		knowledgeService.retryImport(id);
		return Map.of("id", id, "status", "processing");
	}

	@DeleteMapping("/documents/{id}")
	public Map<String, Object> deleteDocument(@PathVariable Long id) {
		knowledgeService.deleteDocument(id);
		return Map.of("deleted", id);
	}

	@PostMapping("/search")
	public Map<String, Object> search(@RequestBody Map<String, Object> body) {
		String query = (String) body.get("query");
		Integer topK = body.get("topK") instanceof Number n ? n.intValue() : null;
		List<KnowledgeService.SearchHit> hits = knowledgeService.search(query, topK);
		return Map.of("query", query == null ? "" : query, "hits", hits);
	}

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
				"status", doc.getStatus() == null ? "ready" : doc.getStatus(),
				"chunkProgress", doc.getChunkProgress(),
				"chunkTotal", doc.getChunkTotal(),
				"createdAt", doc.getCreatedAt() == null ? "" : doc.getCreatedAt().toString());
	}

}
