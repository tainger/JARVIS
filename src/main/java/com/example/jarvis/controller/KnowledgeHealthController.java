package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.jarvis.service.KnowledgeHealthService;

@RestController
@RequestMapping("/api/knowledge/health")
public class KnowledgeHealthController {

	private final KnowledgeHealthService healthService;

	public KnowledgeHealthController(KnowledgeHealthService healthService) {
		this.healthService = healthService;
	}

	@GetMapping("/zombie-docs")
	public Map<String, Object> getZombieDocs() {
		return healthService.getZombieDocs();
	}

	@GetMapping("/blind-spots")
	public List<Map<String, Object>> getBlindSpots() {
		return healthService.getBlindSpots();
	}

	@GetMapping("/doc-heat")
	public List<Map<String, Object>> getDocHeat() {
		return healthService.getDocHeat();
	}
}
