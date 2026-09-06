package com.example.jarvis.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.jarvis.mapper.AnalyticsMapper;

@Service
public class AnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

	private final AnalyticsMapper analyticsMapper;

	public AnalyticsService(AnalyticsMapper analyticsMapper) {
		this.analyticsMapper = analyticsMapper;
	}

	public Map<String, Object> getSummary(Integer days) {
		Map<String, Object> raw = analyticsMapper.selectSummary(days);
		if (raw == null) {
			return Map.of(
					"totalConversations", 0,
					"totalTraces", 0,
					"totalToolCalls", 0,
					"avgStepsPerMessage", 0.0,
					"truncationRate", 0.0,
					"totalDislikes", 0,
					"totalMessages", 0,
					"hallucinationRate", 0.0
			);
		}

		long totalConv = toLong(raw.get("totalConversations"));
		long totalTrunc = toLong(raw.get("totalTruncated"));
		long totalTraces = toLong(raw.get("totalTraces"));
		long dislikes = toLong(raw.get("totalDislikes"));

		double truncationRate = totalTraces > 0
				? Math.round(totalTrunc * 1000.0 / totalTraces) / 10.0
				: 0.0;
		double hallucinationRate = totalConv > 0
				? Math.round(dislikes * 1000.0 / totalConv) / 10.0
				: 0.0;

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("totalConversations", totalConv);
		result.put("totalTraces", totalTraces);
		result.put("totalToolCalls", toLong(raw.get("totalToolCalls")));
		result.put("avgStepsPerMessage", toDouble(raw.get("avgStepsPerMessage")));
		result.put("truncationRate", truncationRate);
		result.put("totalDislikes", dislikes);
		result.put("totalMessages", toLong(raw.get("totalMessages")));
		result.put("hallucinationRate", hallucinationRate);
		return result;
	}

	public List<Map<String, Object>> getToolFrequency() {
		return analyticsMapper.selectToolFrequency();
	}

	public List<Map<String, Object>> getDailyTrend(Integer days) {
		return analyticsMapper.selectDailyTrend(days);
	}

	public List<Map<String, Object>> getSkillDistribution() {
		List<Map<String, Object>> raw = analyticsMapper.selectSkillDistribution();
		long total = raw.stream()
				.mapToLong(m -> toLong(m.get("conversationCount")))
				.sum();
		for (Map<String, Object> m : raw) {
			long count = toLong(m.get("conversationCount"));
			double pct = total > 0
					? Math.round(count * 1000.0 / total) / 10.0
					: 0.0;
			m.put("percentage", pct);
		}
		return raw;
	}

	private long toLong(Object o) {
		if (o == null) return 0;
		if (o instanceof Number n) return n.longValue();
		try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
	}

	private double toDouble(Object o) {
		if (o == null) return 0.0;
		if (o instanceof Number n) return n.doubleValue();
		try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
	}
}
