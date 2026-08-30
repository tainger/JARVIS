package com.example.jarvis.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 评测跑分器（对应 docs/rag-design.md Phase1-#5 评估体系）。
 *
 * <p>两个独立用例，均按环境变量门控，默认 mvn test 不执行：
 * <ul>
 *   <li>{@code evalRetrieval}（RAG_EVAL=true）：检索层纯计算，零 token 成本——
 *       Recall@4 / MRR / 相关-无关分数区分度 / 无关题误注入率，按查询类型分层；
 *       报告输出控制台并写入 target/rag-eval-report.md。</li>
 *   <li>{@code evalGeneration}（RAG_EVAL_LLM=true，需后端已在 8080 运行）：
 *       生成层抽测——走真实 chat 链路，校验答案要素命中与 [n] 引用指向。</li>
 * </ul>
 *
 * <p>运行方式：
 * <pre>
 * RAG_EVAL=true ./mvnw test -Dtest=RagEvalTest                       # 检索层（需本地 Ollama）
 * RAG_EVAL_LLM=true ./mvnw test -Dtest=RagEvalTest -Dgroups=rag-eval # 生成层（需后端运行中）
 * </pre>
 */
@Tag("rag-eval")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true",
		disabledReason = "评测需向量化依赖本地 Ollama：设置 RAG_EVAL=true 后运行")
class RagEvalTest {

	// ---------- 基线阈值：基线校准后不得回退（改检索代码后跑分低于该值即失败） ----------
	private static final double MIN_RECALL_AT_4 = 0.80;
	private static final double MIN_MRR = 0.60;
	private static final double MIN_SEPARATION = 0.10;
	private static final double MAX_WRONG_INJECT_RATE = 0.25;
	/** 允许部分真实题落在模糊带（走 agent 工具路径兜底，生成层评测验证兜底有效性）；0.80 防止 inject-score 被调得过于激进 */
	private static final double MIN_INJECT_RECALL = 0.80;
	private static final int TOP_K = 4;

	private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

	@Autowired
	private KnowledgeService knowledgeService;

	private final ObjectMapper json = new ObjectMapper();

	@JsonIgnoreProperties(ignoreUnknown = true)
	static class EvalCase {
		public String id;
		public String type;
		public String question;
		public String expectDoc;
		public List<String> expectChunkKeywords = List.of();
		public List<String> expectAnswerKeywords = List.of();
		public boolean irrelevant;
	}

	record CaseResult(EvalCase c, int relevantRank, double top1Score,
			List<KnowledgeService.SearchHit> hits, String missDetail) {
	}

	// ================================ 检索层 ================================

	@Test
	void evalRetrieval() throws IOException {
		List<EvalCase> cases = loadCases();
		List<CaseResult> relevant = new ArrayList<>();
		List<CaseResult> irrelevant = new ArrayList<>();

		for (EvalCase c : cases) {
			List<KnowledgeService.SearchHit> hits = knowledgeService.search(c.question, TOP_K);
			double top1 = hits.isEmpty() ? 0 : hits.get(0).score();
			int rank = c.irrelevant ? 0 : relevantRank(c, hits);
			CaseResult r = new CaseResult(c, rank, top1, hits,
					rank == 0 ? missDetail(c, hits) : null);
			(c.irrelevant ? irrelevant : relevant).add(r);
		}

		long recallHits = relevant.stream().filter(r -> r.relevantRank() > 0).count();
		double recallAt4 = (double) recallHits / relevant.size();
		double mrr = relevant.stream().mapToDouble(r -> r.relevantRank() == 0 ? 0
				: 1.0 / r.relevantRank()).average().orElse(0);
		double sep = relevant.stream().mapToDouble(CaseResult::top1Score).average().orElse(0)
				- irrelevant.stream().mapToDouble(CaseResult::top1Score).average().orElse(0);
		long wrongInject = irrelevant.stream()
				.filter(r -> knowledgeService.buildInjection(r.c().question, TOP_K) != null)
				.count();
		double wrongInjectRate = (double) wrongInject / irrelevant.size();
		Set<String> marginalIds = new HashSet<>();
		for (CaseResult r : relevant) {
			if (knowledgeService.buildInjection(r.c().question, TOP_K) == null) {
				marginalIds.add(r.c().id);
			}
		}
		double injectRecall = 1 - marginalIds.size() / (double) relevant.size();

		String report = renderReport(relevant, irrelevant, recallAt4, mrr, sep,
				wrongInject, wrongInjectRate, injectRecall, marginalIds);
		System.out.println(report);
		Path out = Path.of("target", "rag-eval-report.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report);

		assertTrue(recallAt4 >= MIN_RECALL_AT_4,
				"Recall@4=%.3f 低于基线 %.2f（报告见 %s）".formatted(recallAt4, MIN_RECALL_AT_4, out));
		assertTrue(mrr >= MIN_MRR, "MRR=%.3f 低于基线 %.2f".formatted(mrr, MIN_MRR));
		assertTrue(sep >= MIN_SEPARATION,
				"相关-无关分数区分度=%.3f 低于基线 %.2f".formatted(sep, MIN_SEPARATION));
		assertTrue(wrongInjectRate <= MAX_WRONG_INJECT_RATE,
				"误注入率=%.2f 高于上限 %.2f（考虑调高 rag.retrieval.inject-score）"
						.formatted(wrongInjectRate, MAX_WRONG_INJECT_RATE));
		assertTrue(injectRecall >= MIN_INJECT_RECALL,
				"注入召回=%.2f 低于基线 %.2f——inject-score 门槛在误杀真实知识问题"
						.formatted(injectRecall, MIN_INJECT_RECALL));
	}

	/** 命中判定：文档匹配，且块内容含任一标注关键词（把"只答对文档"的水分挤掉） */
	private boolean isRelevantHit(EvalCase c, KnowledgeService.SearchHit hit) {
		if (!hit.documentTitle().equals(c.expectDoc)) {
			return false;
		}
		String content = hit.content().toLowerCase();
		return c.expectChunkKeywords.stream().anyMatch(k -> content.contains(k.toLowerCase()));
	}

	private int relevantRank(EvalCase c, List<KnowledgeService.SearchHit> hits) {
		for (int i = 0; i < hits.size(); i++) {
			if (isRelevantHit(c, hits.get(i))) {
				return i + 1;
			}
		}
		return 0;
	}

	private String missDetail(EvalCase c, List<KnowledgeService.SearchHit> hits) {
		StringBuilder sb = new StringBuilder(c.id + "「" + c.question + "」Top%d: ".formatted(hits.size()));
		for (KnowledgeService.SearchHit h : hits) {
			sb.append("\n    %.3f %s#%d（%.40s…）".formatted(h.score(), h.documentTitle(),
					h.seq(), h.content().replace("\n", " ")));
		}
		return sb.toString();
	}

	private String question(EvalCase c) {
		return c.question;
	}

	private String renderReport(List<CaseResult> relevant, List<CaseResult> irrelevant,
			double recallAt4, double mrr, double sep, long wrongInject, double wrongInjectRate,
			double injectRecall, Set<String> marginalIds) {
		StringBuilder sb = new StringBuilder();
		sb.append("# JARVIS RAG 评测报告\n\n");
		sb.append("- 用例：%d 相关 + %d 无关 ｜ Top-K=%d ｜ 评分：0.75×cosine + 0.25×词面\n\n"
				.formatted(relevant.size(), irrelevant.size(), TOP_K));

		sb.append("## 总体指标\n\n");
		sb.append("| 指标 | 值 | 基线 | 结果 |\n|---|---|---|---|\n");
		sb.append("| Recall@%d | %.3f | ≥ %.2f | %s |\n".formatted(TOP_K, recallAt4, MIN_RECALL_AT_4, mark(recallAt4 >= MIN_RECALL_AT_4)));
		sb.append("| MRR | %.3f | ≥ %.2f | %s |\n".formatted(mrr, MIN_MRR, mark(mrr >= MIN_MRR)));
		sb.append("| 分数区分度（相关Top1均值−无关Top1均值）| %.3f | ≥ %.2f | %s |\n".formatted(sep, MIN_SEPARATION, mark(sep >= MIN_SEPARATION)));
		sb.append("| 误注入率 | %d/%d=%.2f | ≤ %.2f | %s |\n".formatted(wrongInject, irrelevant.size(), wrongInjectRate, MAX_WRONG_INJECT_RATE, mark(wrongInjectRate <= MAX_WRONG_INJECT_RATE)));
		sb.append("| 注入召回（强相关题确实被注入）| %.3f | ≥ %.2f | %s |\n".formatted(injectRecall, MIN_INJECT_RECALL, mark(injectRecall >= MIN_INJECT_RECALL)));

		sb.append("\n## 分查询类型\n\n");
		sb.append("| 类型 | 用例 | Recall@%d | MRR | Top1 均分 |\n|---|---|---|---|---|\n".formatted(TOP_K));
		Map<String, List<CaseResult>> byType = new LinkedHashMap<>();
		for (CaseResult r : relevant) {
			byType.computeIfAbsent(r.c().type, k -> new ArrayList<>()).add(r);
		}
		byType.entrySet().stream().sorted(Comparator.comparingDouble(e ->
				-e.getValue().stream().mapToDouble(r -> r.relevantRank() == 0 ? 0 : 1.0 / r.relevantRank()).average().orElse(0)))
				.forEach(e -> {
					List<CaseResult> rs = e.getValue();
					double rc = rs.stream().filter(r -> r.relevantRank() > 0).count() / (double) rs.size();
					double mr = rs.stream().mapToDouble(r -> r.relevantRank() == 0 ? 0 : 1.0 / r.relevantRank()).average().orElse(0);
					double ts = rs.stream().mapToDouble(CaseResult::top1Score).average().orElse(0);
					sb.append("| %s | %d | %.3f | %.3f | %.3f |\n".formatted(e.getKey(), rs.size(), rc, mr, ts));
				});

		sb.append("\n## 未命中明细（Recall 损失）\n");
		List<CaseResult> misses = relevant.stream().filter(r -> r.relevantRank() == 0).toList();
		if (misses.isEmpty()) {
			sb.append("\n无 ✓\n");
		}
		else {
			misses.forEach(r -> sb.append("\n- ").append(r.missDetail()));
		}

		sb.append("\n## 模糊带明细（Top1 未达 inject-score，未自动注入，靠 agent 工具路径兜底）\n");
		List<CaseResult> marginal = relevant.stream().filter(r -> marginalIds.contains(r.c().id)).toList();
		if (marginal.isEmpty()) {
			sb.append("\n无 ✓\n");
		}
		else {
			for (CaseResult r : marginal) {
				sb.append("\n- %s「%s」Top1=%.3f →《%s》".formatted(r.c().id, question(r.c()),
						r.top1Score(), r.c().expectDoc));
			}
		}

		sb.append("\n## 无关题明细\n\n| 问题 | Top1 分数 | 误注入 |\n|---|---|---|\n");
		for (CaseResult r : irrelevant) {
			boolean injected = knowledgeService.buildInjection(r.c().question, TOP_K) != null;
			sb.append("| %s | %.3f | %s |\n".formatted(question(r.c()), r.top1Score(), injected ? "⚠️ 是" : "否"));
		}
		return sb.toString();
	}

	private String mark(boolean pass) {
		return pass ? "✓" : "❌";
	}

	// ================================ 生成层 ================================

	@Test
	@EnabledIfEnvironmentVariable(named = "RAG_EVAL_LLM", matches = "true",
			disabledReason = "生成层评测走真实 DeepSeek（消耗 token）：设置 RAG_EVAL_LLM=true 且后端运行中")
	void evalGeneration() throws Exception {
		List<EvalCase> cases = loadCases().stream()
				.filter(c -> !c.irrelevant && !c.expectAnswerKeywords.isEmpty())
				.toList();
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10)).build();

		int answerHit = 0;
		int citationTotal = 0;
		int citationCorrect = 0;
		StringBuilder detail = new StringBuilder();

		for (EvalCase c : cases) {
			Map<String, Object> body = Map.of("message", c.question);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:8080/api/agent/chat"))
					.header("Content-Type", "application/json")
					.timeout(Duration.ofSeconds(120))
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
					.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			Map<String, Object> result = json.readValue(resp.body(), new TypeReference<>() { });
			String answer = String.valueOf(result.getOrDefault("answer", ""));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> sources = (List<Map<String, Object>>) result.getOrDefault("sources", List.of());

			boolean keywordsOk = c.expectAnswerKeywords.stream().allMatch(answer::contains);
			if (keywordsOk) {
				answerHit++;
			}
			String citationNote = "无引用";
			Matcher m = CITATION.matcher(answer);
			if (m.find() && !sources.isEmpty()) {
				citationTotal++;
				int ref = Integer.parseInt(m.group(1));
				Object citedDoc = ref >= 1 && ref <= sources.size()
						? sources.get(ref - 1).get("documentTitle") : "?";
				boolean ok = c.expectDoc.equals(citedDoc);
				if (ok) {
					citationCorrect++;
				}
				citationNote = "[" + ref + "]→" + citedDoc + (ok ? " ✓" : " ✗ 应指向《" + c.expectDoc + "》");
			}
			detail.append("| %s | %s | %s | %s |\n".formatted(c.id, keywordsOk ? "✓" : "❌ 缺"
					+ c.expectAnswerKeywords, citationNote,
					answer.length() > 50 ? answer.substring(0, 50) + "…" : answer));
		}

		double answerRate = (double) answerHit / cases.size();
		double citationRate = citationTotal == 0 ? 1.0 : (double) citationCorrect / citationTotal;
		String gen = """
				\n## 生成层评测（%d 条，真实链路）

				| 指标 | 值 | 基线 | 结果 |
				|---|---|---|---|
				| 答案要素命中 | %d/%d=%.2f | ≥ 0.80 | %s |
				| 引用指向正确 | %d/%d=%.2f | ≥ 0.80 | %s |

				| 用例 | 要素 | 引用 | 回答摘要 |
				|---|---|---|---|
				""".formatted(cases.size(), answerHit, cases.size(), answerRate,
				mark(answerRate >= 0.80), citationCorrect, citationTotal, citationRate,
				mark(citationRate >= 0.80)) + detail;
		System.out.println(gen);
		Files.writeString(Path.of("target", "rag-eval-report-generation.md"), gen);

		assertTrue(answerRate >= 0.80, "答案要素命中率 %.2f 低于 0.80".formatted(answerRate));
		assertTrue(citationRate >= 0.80, "引用指向正确率 %.2f 低于 0.80".formatted(citationRate));
	}

	private List<EvalCase> loadCases() throws IOException {
		return json.readValue(new ClassPathResource("rag-eval-cases.json").getInputStream(),
				new TypeReference<List<EvalCase>>() { });
	}

}
