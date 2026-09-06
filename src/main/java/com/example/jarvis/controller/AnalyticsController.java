package com.example.jarvis.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.jarvis.service.AnalyticsService;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

	private final AnalyticsService analyticsService;

	public AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@GetMapping("/summary")
	public Map<String, Object> getSummary(
			@RequestParam(value = "days", required = false) Integer days) {
		return analyticsService.getSummary(days);
	}

	@GetMapping("/tool-frequency")
	public List<Map<String, Object>> getToolFrequency() {
		return analyticsService.getToolFrequency();
	}

	@GetMapping("/daily-trend")
	public List<Map<String, Object>> getDailyTrend(
			@RequestParam(value = "days", required = false) Integer days) {
		return analyticsService.getDailyTrend(days);
	}

	@GetMapping("/skill-distribution")
	public List<Map<String, Object>> getSkillDistribution() {
		return analyticsService.getSkillDistribution();
	}
}
