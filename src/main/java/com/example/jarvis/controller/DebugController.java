package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.jarvis.model.DebugBreakpoint;
import com.example.jarvis.service.DebugService;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

	private final DebugService debugService;

	public DebugController(DebugService debugService) {
		this.debugService = debugService;
	}

	@GetMapping("/{conversationId}/steps")
	public Map<String, Object> getSteps(@PathVariable Long conversationId) {
		return debugService.getSteps(conversationId, currentUserId());
	}

	@GetMapping("/{conversationId}/context/{messageId}/{stepIndex}")
	public Map<String, Object> getContext(@PathVariable Long conversationId,
			@PathVariable Long messageId, @PathVariable int stepIndex) {
		return debugService.buildContext(conversationId, messageId, stepIndex, currentUserId());
	}

	@PostMapping("/breakpoints")
	public Map<String, Object> manageBreakpoint(@RequestBody Map<String, Object> body) {
		Long userId = currentUserId();
		Long conversationId = ((Number) body.get("conversationId")).longValue();
		Long messageId = ((Number) body.get("messageId")).longValue();
		int stepIndex = ((Number) body.get("stepIndex")).intValue();
		String action = (String) body.get("action");
		String note = (String) body.get("note");

		if ("add".equals(action)) {
			debugService.addBreakpoint(userId, conversationId, messageId, stepIndex, note);
		} else if ("remove".equals(action)) {
			debugService.removeBreakpoint(userId, conversationId, messageId, stepIndex);
		}

		return Map.of("ok", true);
	}

	@GetMapping("/{conversationId}/breakpoints")
	public List<DebugBreakpoint> getBreakpoints(@PathVariable Long conversationId) {
		return debugService.getBreakpoints(currentUserId(), conversationId);
	}

	@GetMapping("/diff")
	public Map<String, Object> diff(@RequestParam Long convA, @RequestParam Long convB) {
		return debugService.diffConversations(convA, convB, currentUserId());
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
