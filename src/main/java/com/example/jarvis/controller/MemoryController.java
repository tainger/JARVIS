package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import com.example.jarvis.mapper.UserMemoryMapper;
import com.example.jarvis.model.UserMemory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户长期记忆管理 API：列表/删除，所有操作按当前登录用户隔离。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

	private final UserMemoryMapper userMemoryMapper;

	public MemoryController(UserMemoryMapper userMemoryMapper) {
		this.userMemoryMapper = userMemoryMapper;
	}

	/**
	 * 查询当前用户的长期记忆列表
	 */
	@GetMapping
	public List<UserMemory> list() {
		Long userId = currentUserId();
		List<UserMemory> memories = userMemoryMapper.findByUserId(userId);
		// 不返回 embedding 向量（前端不需要，且数据量大）
		memories.forEach(m -> m.setEmbedding(null));
		return memories;
	}

	/**
	 * 删除（软删除）一条长期记忆
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		Long userId = currentUserId();
		if (userMemoryMapper.countByIdAndUserId(id, userId) == 0) {
			return ResponseEntity.status(403).body(Map.of("error", "无权操作该记忆"));
		}
		userMemoryMapper.softDelete(id);
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
