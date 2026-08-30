package com.example.jarvis.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.jarvis.rag.KnowledgeService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 评测数据模型：标注用例、单例结果、两层指标与归档 summary。
 * 归档 summary 的字段结构即 docs/eval/history/&lt;runId&gt;/summary.json 的契约。
 */
public final class EvalModels {

	private EvalModels() {
	}

	/** 标注用例（对应 src/test/resources/rag-eval-cases.json 的一条）。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class EvalCase {

		public String id;
		public String type;
		public String question;
		public String expectDoc;
		public List<String> expectChunkKeywords = List.of();
		public List<String> expectAnswerKeywords = List.of();
		public boolean irrelevant;
	}

	/** 单条检索用例的执行结果。relevantRank：首个命中块的排名（1 起，0=未命中）；injected：该查询会被 buildInjection 自动注入。 */
	public record CaseResult(EvalCase c, int relevantRank, double top1Score,
			List<KnowledgeService.SearchHit> hits, String missDetail, long latencyMs, boolean injected) {
	}

	/** 生成层单例结果。 */
	public record GenRow(EvalCase c, boolean keywordsOk, String citationNote,
			String answer, long e2eMs, Integer faithScore, String faithReason) {
	}

	/** 检索层一次运行的产物。 */
	public record RetrievalOutcome(List<CaseResult> relevant, List<CaseResult> irrelevant,
			double recallAt4, double mrr, double separation,
			long wrongInject, double wrongInjectRate, double injectRecall,
			Map<String, Long> latenciesByCase) {

		/** 指标名 → 值（归档与 diff 的统一出口，顺序即报告展示顺序）。 */
		public Map<String, Double> metrics() {
			LinkedHashMap<String, Double> m = new LinkedHashMap<>();
			m.put("recallAt4", recallAt4);
			m.put("mrr", mrr);
			m.put("separation", separation);
			m.put("wrongInjectRate", wrongInjectRate);
			m.put("injectRecall", injectRecall);
			return m;
		}
	}

	/** 生成层一次运行的产物。 */
	public record GenerationOutcome(List<GenRow> rows, int cases,
			int answerHit, double answerRate,
			int citationTotal, int citationCorrect, double citationRate,
			double faithfulnessAvg, double judgeCoverage) {

		public Map<String, Double> metrics() {
			LinkedHashMap<String, Double> m = new LinkedHashMap<>();
			m.put("answerHitRate", answerRate);
			m.put("citationAccuracy", citationRate);
			m.put("faithfulnessAvg", faithfulnessAvg);
			return m;
		}
	}
}
