package com.example.jarvis.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 评测报告 Markdown 渲染：检索层与生成层各一份，纯函数无副作用。
 * 基线阈值常量同时被测试薄壳引用作断言——改这里即改断言。
 */
public final class EvalReportRenderer {

	// ---------- 基线阈值：基线校准后不得回退（改检索代码后跑分低于该值即失败） ----------
	public static final double MIN_RECALL_AT_4 = 0.80;
	public static final double MIN_MRR = 0.60;
	public static final double MIN_SEPARATION = 0.10;
	public static final double MAX_WRONG_INJECT_RATE = 0.25;
	/** 允许部分真实题落在模糊带（走 agent 工具路径兜底，生成层评测验证兜底有效性）；0.80 防止 inject-score 被调得过于激进 */
	public static final double MIN_INJECT_RECALL = 0.80;
	public static final double MIN_ANSWER_RATE = 0.80;
	public static final double MIN_CITATION_RATE = 0.80;
	/** 忠实度（LLM-as-judge）：平均分与评分覆盖率门槛（任务组5）。 */
	public static final double MIN_FAITHFULNESS_AVG = 4.0;
	public static final double MIN_JUDGE_COVERAGE = 0.80;

	private EvalReportRenderer() {
	}

	// ================================ 检索层 ================================

	public static String renderRetrieval(EvalModels.RetrievalOutcome o) {
		int topK = EvalRunner.TOP_K;
		StringBuilder sb = new StringBuilder();
		sb.append("# JARVIS RAG 评测报告\n\n");
		sb.append("- 用例：%d 相关 + %d 无关 ｜ Top-K=%d ｜ 评分：0.75×cosine + 0.25×词面\n\n"
				.formatted(o.relevant().size(), o.irrelevant().size(), topK));

		sb.append("## 总体指标\n\n");
		sb.append("| 指标 | 值 | 基线 | 结果 |\n|---|---|---|---|\n");
		sb.append("| Recall@%d | %.3f | ≥ %.2f | %s |\n"
				.formatted(topK, o.recallAt4(), MIN_RECALL_AT_4, mark(o.recallAt4() >= MIN_RECALL_AT_4)));
		sb.append("| MRR | %.3f | ≥ %.2f | %s |\n"
				.formatted(o.mrr(), MIN_MRR, mark(o.mrr() >= MIN_MRR)));
		sb.append("| 分数区分度（相关Top1均值−无关Top1均值）| %.3f | ≥ %.2f | %s |\n"
				.formatted(o.separation(), MIN_SEPARATION, mark(o.separation() >= MIN_SEPARATION)));
		sb.append("| 误注入率 | %d/%d=%.2f | ≤ %.2f | %s |\n"
				.formatted(o.wrongInject(), o.irrelevant().size(), o.wrongInjectRate(),
						MAX_WRONG_INJECT_RATE, mark(o.wrongInjectRate() <= MAX_WRONG_INJECT_RATE)));
		sb.append("| 注入召回（强相关题确实被注入）| %.3f | ≥ %.2f | %s |\n"
				.formatted(o.injectRecall(), MIN_INJECT_RECALL, mark(o.injectRecall() >= MIN_INJECT_RECALL)));

		sb.append("\n## 分查询类型\n\n");
		sb.append("| 类型 | 用例 | Recall@%d | MRR | Top1 均分 |\n|---|---|---|---|---|\n".formatted(topK));
		sb.append(groupTable(o.relevant(), cr -> cr.c().type));

		sb.append("\n## 分文档\n\n");
		sb.append("| 文档 | 用例 | Recall@%d | MRR | Top1 均分 |\n|---|---|---|---|---|\n".formatted(topK));
		sb.append(groupTable(o.relevant(), cr -> cr.c().expectDoc));

		sb.append("\n## 未命中明细（Recall 损失）\n");
		List<EvalModels.CaseResult> misses = o.relevant().stream().filter(r -> r.relevantRank() == 0).toList();
		if (misses.isEmpty()) {
			sb.append("\n无 ✓\n");
		}
		else {
			misses.forEach(r -> sb.append("\n- ").append(r.missDetail()));
		}

		sb.append("\n## 模糊带明细（Top1 未达 inject-score，未自动注入，靠 agent 工具路径兜底）\n");
		List<EvalModels.CaseResult> marginal = o.relevant().stream().filter(r -> !r.injected()).toList();
		if (marginal.isEmpty()) {
			sb.append("\n无 ✓\n");
		}
		else {
			for (EvalModels.CaseResult r : marginal) {
				sb.append("\n- %s「%s」Top1=%.3f →《%s》".formatted(r.c().id, r.c().question,
						r.top1Score(), r.c().expectDoc));
			}
		}

		sb.append("\n## 无关题明细\n\n| 问题 | Top1 分数 | 误注入 |\n|---|---|---|\n");
		for (EvalModels.CaseResult r : o.irrelevant()) {
			sb.append("| %s | %.3f | %s |\n".formatted(r.c().question, r.top1Score(),
					r.injected() ? "⚠️ 是" : "否"));
		}

		sb.append("\n## 时延（检索 search）\n\n");
		sb.append("| p50 | p95 |\n|---|---|\n");
		List<Long> all = new ArrayList<>(o.latenciesByCase().values());
		sb.append("| %dms | %dms |\n".formatted(percentileMs(all, 50), percentileMs(all, 95)));
		sb.append("\n| 类型 | 均值 |\n|---|---|\n");
		avgByType(o).forEach((type, avg) ->
				sb.append("| %s | %dms |\n".formatted(type, Math.round(avg))));
		return sb.toString();
	}

	// ================================ 生成层 ================================

	public static String renderGeneration(EvalModels.GenerationOutcome g, boolean judgeEnabled) {
		StringBuilder detail = new StringBuilder();
		for (EvalModels.GenRow r : g.rows()) {
			String faith = judgeEnabled
					? (r.faithScore() == null ? "—(未评)" : "★%d %s".formatted(r.faithScore(),
							r.faithReason() == null ? "" : r.faithReason()))
					: "未启用";
			detail.append("| %s | %s | %s | %s | %s |\n".formatted(r.c().id,
					r.keywordsOk() ? "✓" : "❌ 缺" + r.c().expectAnswerKeywords, r.citationNote(),
					faith,
					r.answer().length() > 50 ? r.answer().substring(0, 50) + "…" : r.answer()));
		}
		String summaryRows = """
				| 答案要素命中 | %d/%d=%.2f | ≥ 0.80 | %s |
				| 引用指向正确 | %d/%d=%.2f | ≥ 0.80 | %s |
				""".formatted(g.answerHit(), g.cases(), g.answerRate(), mark(g.answerRate() >= MIN_ANSWER_RATE),
				g.citationCorrect(), g.citationTotal(), g.citationRate(),
				mark(g.citationRate() >= MIN_CITATION_RATE));
		if (judgeEnabled) {
			summaryRows += """
					| 忠实度均分（LLM judge） | %.2f | ≥ %.1f | %s |
					| 评分覆盖率 | %.2f | ≥ %.2f | %s |
					""".formatted(g.faithfulnessAvg(), MIN_FAITHFULNESS_AVG,
					mark(g.faithfulnessAvg() >= MIN_FAITHFULNESS_AVG),
					g.judgeCoverage(), MIN_JUDGE_COVERAGE,
					mark(g.judgeCoverage() >= MIN_JUDGE_COVERAGE));
		}
		return """
				\n## 生成层评测（%d 条，真实链路）

				| 指标 | 值 | 基线 | 结果 |
				|---|---|---|---|
				%s

				| 用例 | 要素 | 引用 | 忠实度 | 回答摘要 |
				|---|---|---|---|---|
				""".formatted(g.cases(), summaryRows) + detail
				+ "\n## 时延（生成端到端）\n\n| p50 | p95 |\n|---|---|\n| %dms | %dms |\n".formatted(
						percentileMs(g.rows().stream().map(EvalModels.GenRow::e2eMs).toList(), 50),
						percentileMs(g.rows().stream().map(EvalModels.GenRow::e2eMs).toList(), 95));
	}

	// ================================ 时延与分层（指标深度） ================================

	/** 分位近似：升序后取 ceil(p·n)-1 下标（样本量小，足够定位尾部时延）。 */
	public static long percentileMs(List<Long> ms, double p) {
		if (ms == null || ms.isEmpty()) {
			return 0;
		}
		List<Long> sorted = new ArrayList<>(ms);
		java.util.Collections.sort(sorted);
		int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
	}

	/** 检索层分类型均值（报告与 summary 共用）。 */
	public static Map<String, Double> avgByType(EvalModels.RetrievalOutcome o) {
		Map<String, List<Long>> byType = new LinkedHashMap<>();
		List<EvalModels.CaseResult> all = new ArrayList<>(o.relevant());
		all.addAll(o.irrelevant());
		for (EvalModels.CaseResult r : all) {
			byType.computeIfAbsent(r.c().type, k -> new ArrayList<>()).add(r.latencyMs());
		}
		Map<String, Double> out = new LinkedHashMap<>();
		byType.forEach((k, v) -> out.put(k, v.stream().mapToLong(Long::longValue).average().orElse(0)));
		return out;
	}

	/** summary.latency（检索层）：p50/p95 + 分类型均值 + 逐条耗时（可回查）。 */
	public static Map<String, Double> retrievalLatencySummary(EvalModels.RetrievalOutcome o) {
		Map<String, Double> out = new LinkedHashMap<>();
		List<Long> all = new ArrayList<>(o.latenciesByCase().values());
		out.put("searchP50", (double) percentileMs(all, 50));
		out.put("searchP95", (double) percentileMs(all, 95));
		avgByType(o).forEach((k, v) -> out.put("searchAvg." + k, v));
		o.latenciesByCase().forEach((id, ms) -> out.put("case." + id, (double) ms));
		return out;
	}

	/** summary.latency（生成层）：端到端 p50/p95 + 逐条耗时。 */
	public static Map<String, Double> generationLatencySummary(EvalModels.GenerationOutcome g) {
		Map<String, Double> out = new LinkedHashMap<>();
		List<Long> all = g.rows().stream().map(EvalModels.GenRow::e2eMs).toList();
		out.put("e2eP50", (double) percentileMs(all, 50));
		out.put("e2eP95", (double) percentileMs(all, 95));
		g.rows().forEach(r -> out.put("case." + r.c().id, (double) r.e2eMs()));
		return out;
	}

	// ================================ 趋势对比 ================================

	/** 与上次同 suite 归档的 diff 表；仅渲染两次共有的指标。 */
	public static String renderDiffTable(EvalRunSummary prev, Map<String, Double> current) {
		StringBuilder sb = new StringBuilder("\n## 与上次对比（%s）\n\n".formatted(prev.runId));
		sb.append("| 指标 | 上次 | 本次 | 变化 | 评价 |\n|---|---|---|---|---|\n");
		current.forEach((k, v) -> {
			Double pv = prev.metrics.get(k);
			if (pv == null) {
				return;
			}
			double d = v - pv;
			String verdict;
			if (Math.abs(d) < 0.0005) {
				verdict = "→ 持平";
			}
			else {
				boolean good = (d > 0) == EvalTrendDiff.isUpGood(k);
				verdict = (good ? "↑ 变好" : "↓ 变坏") + (EvalTrendDiff.isSignificant(d) ? " ⚠️" : "");
			}
			sb.append("| %s | %.3f | %.3f | %+.3f | %s |\n".formatted(k, pv, v, d, verdict));
		});
		return sb.toString();
	}

	/** 首次运行无历史可对比时的占位说明。 */
	public static String firstRunNote() {
		return "\n> 基线首次建立（无历史归档可对比）\n";
	}

	private static String mark(boolean pass) {
		return pass ? "✓" : "❌";
	}

	/** 分组指标表（分查询类型/分文档共用）：组内用例数、Recall@K、MRR、Top1 均分。 */
	private static String groupTable(List<EvalModels.CaseResult> cases,
			Function<EvalModels.CaseResult, String> key) {
		LinkedHashMap<String, List<EvalModels.CaseResult>> groups = new LinkedHashMap<>();
		cases.forEach(cr -> groups.computeIfAbsent(key.apply(cr), k -> new ArrayList<>()).add(cr));
		int topK = EvalRunner.TOP_K;
		StringBuilder sb = new StringBuilder();
		groups.forEach((group, list) -> {
			double recall = list.stream().filter(r -> r.relevantRank() > 0).count() / (double) list.size();
			double mrr = list.stream()
					.mapToDouble(r -> r.relevantRank() == 0 ? 0 : 1.0 / r.relevantRank())
					.average().orElse(0);
			double top1 = list.stream().mapToDouble(EvalModels.CaseResult::top1Score).average().orElse(0);
			sb.append("| %s | %d | %.3f | %.3f | %.3f |\n".formatted(group, list.size(), recall, mrr, top1));
		});
		return sb.toString();
	}
}
