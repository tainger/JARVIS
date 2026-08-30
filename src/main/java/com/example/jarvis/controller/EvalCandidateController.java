package com.example.jarvis.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.jarvis.dto.CandidatePromoteRequest;
import com.example.jarvis.model.EvalCandidate;
import com.example.jarvis.service.EvalCandidateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 评测候选池：Chat 👎 / 手动提交的坏 case 入池，triage 后进标注集。
 * 鉴权沿用 SecurityConfig（/api/** 需登录）。
 */
@RestController
@RequestMapping("/api/knowledge/eval/candidates")
public class EvalCandidateController {

	private final EvalCandidateService service;

	public EvalCandidateController(EvalCandidateService service) {
		this.service = service;
	}

	/** 入池。重复 pending（同题规范化后）返回 409 + 既有 id。 */
	@PostMapping
	public ResponseEntity<Object> submit(@RequestBody Map<String, String> body) {
		String question = body.get("question");
		if (question == null || question.isBlank()) {
			throw new IllegalArgumentException("question 不能为空");
		}
		if (question.length() > 500) {
			throw new IllegalArgumentException("question 过长（上限 500 字符）");
		}
		EvalCandidate dup = service.findPendingDuplicate(question);
		if (dup != null) {
			Map<String, Object> conflict = new LinkedHashMap<>();
			conflict.put("error", "相同问题已在待处理池");
			conflict.put("existingId", dup.getId());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
		}
		EvalCandidate created = service.submit(question, body.get("note"), body.get("source"),
				body.get("chatRef"));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	/** 分页倒序列表。status: pending（默认）/ promoted / discarded。 */
	@GetMapping
	public Map<String, Object> list(@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size) {
		EvalCandidateService.Page p = service.list(status, page, size);
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("total", p.total());
		resp.put("page", page);
		resp.put("size", size);
		resp.put("items", p.items());
		return resp;
	}

	/** 转正：补全标注 → 追加写标注集（回读校验）→ 置 promoted。重复 triage 409。 */
	@PostMapping("/{id}/promote")
	public Map<String, Object> promote(@PathVariable Long id,
			@RequestBody CandidatePromoteRequest req) throws IOException {
		if (req.type() == null || req.type().isBlank()) {
			throw new IllegalArgumentException("type 不能为空");
		}
		if (req.expectDoc() == null || req.expectDoc().isBlank()) {
			throw new IllegalArgumentException("expectDoc 不能为空");
		}
		EvalCandidateService.PromoteResult r = service.promote(id, req.type().trim(),
				req.expectDoc().trim(), req.expectChunkKeywords(), req.expectAnswerKeywords());
		return Map.of("candidateId", r.candidateId(), "caseId", r.caseId(), "status", "promoted");
	}

	/** 丢弃：只对 pending 生效，重复 triage 409。 */
	@PostMapping("/{id}/discard")
	public Map<String, Object> discard(@PathVariable Long id) {
		service.discard(id);
		return Map.of("candidateId", id, "status", "discarded");
	}
}
