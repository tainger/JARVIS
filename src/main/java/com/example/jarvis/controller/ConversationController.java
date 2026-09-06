package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import com.example.jarvis.model.Conversation;
import com.example.jarvis.model.Message;
import com.example.jarvis.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理 API：列表/创建/详情/重命名/删除，所有操作按当前登录用户隔离。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ConversationService conversationService;

	public ConversationController(ConversationService conversationService) {
		this.conversationService = conversationService;
	}

	/**
	 * 查询当前用户的会话列表
	 */
	@GetMapping
	public List<Conversation> list() {
		return conversationService.listByUser(currentUserId());
	}

	/**
	 * 创建新会话
	 */
	@PostMapping
	public Conversation create(@RequestBody(required = false) Map<String, String> body) {
		String title = body != null ? body.get("title") : null;
		return conversationService.create(currentUserId(), title);
	}

	/**
	 * 获取会话详情 + 分页消息历史
	 */
	@GetMapping("/{id}")
	public ResponseEntity<?> getById(@PathVariable Long id,
			@RequestParam(defaultValue = "0") int offset,
			@RequestParam(defaultValue = "50") int limit) {
		Long userId = currentUserId();
		Conversation conversation = conversationService.getByIdForUser(id, userId);
		if (conversation == null) {
			return ResponseEntity.status(403).body(Map.of("error", "无权访问该会话"));
		}
		List<Message> messages = conversationService.getMessages(id, offset, limit);
		return ResponseEntity.ok(Map.of(
				"conversation", conversation,
				"messages", messages));
	}

	/**
	 * 重命名会话
	 */
	@PutMapping("/{id}")
	public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
		Long userId = currentUserId();
		String title = body.get("title");
		boolean ok = conversationService.rename(id, userId, title);
		if (!ok) {
			return ResponseEntity.status(403).body(Map.of("error", "无权操作该会话"));
		}
		return ResponseEntity.ok(Map.of("success", true));
	}

	/**
	 * 删除会话（级联删除消息）
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		Long userId = currentUserId();
		boolean ok = conversationService.delete(id, userId);
		if (!ok) {
			return ResponseEntity.status(403).body(Map.of("error", "无权操作该会话"));
		}
		return ResponseEntity.ok(Map.of("success", true));
	}

	private Long currentUserId() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof Long id) {
			return id;
		}
		if (principal instanceof Integer id) {
			return id.longValue();
		}
		throw new IllegalStateException("无法获取当前用户身份，请重新登录");
	}

}
