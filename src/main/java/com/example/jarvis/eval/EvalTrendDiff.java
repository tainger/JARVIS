package com.example.jarvis.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 趋势对比的语义规则（design.md 决策4）：指标方向语义 + 显著变化阈值。
 * 主侧（评测中心 API 的 JSON diff）与测试侧（报告 Markdown diff 表）共用，
 * 避免两处各自维护一份方向表造成漂移。
 */
public final class EvalTrendDiff {

	/** 绝对变化量达到该值才在报告/API 中标记"显著"（⚠️）。 */
	public static final double SIGNIFICANT_DELTA = 0.03;

	/** 变化量绝对值低于该值视为持平。 */
	public static final double FLAT_EPSILON = 0.0005;

	/** 方向语义：true=升好，false=降好；未登记的指标默认升好。 */
	private static final Map<String, Boolean> UP_IS_GOOD = Map.ofEntries(
			Map.entry("recallAt4", true),
			Map.entry("mrr", true),
			Map.entry("separation", true),
			Map.entry("wrongInjectRate", false),
			Map.entry("injectRecall", true),
			Map.entry("answerHitRate", true),
			Map.entry("citationAccuracy", true),
			Map.entry("faithfulnessAvg", true),
			Map.entry("p50", false),
			Map.entry("p95", false));

	private EvalTrendDiff() {
	}

	/** 该指标是否"升即变好"。 */
	public static boolean isUpGood(String metric) {
		return UP_IS_GOOD.getOrDefault(metric, true);
	}

	/** 是否显著变化。 */
	public static boolean isSignificant(double delta) {
		return Math.abs(delta) >= SIGNIFICANT_DELTA;
	}

	/** JSON 语义标签：flat / up-good / down-good / up-bad / down-bad。 */
	public static String verdict(String metric, double delta) {
		if (Math.abs(delta) < FLAT_EPSILON) {
			return "flat";
		}
		boolean good = (delta > 0) == isUpGood(metric);
		return delta > 0 ? (good ? "up-good" : "up-bad") : (good ? "down-good" : "down-bad");
	}
}
