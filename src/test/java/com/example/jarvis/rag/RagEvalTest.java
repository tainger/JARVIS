package com.example.jarvis.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.example.jarvis.eval.DeepSeekEvalJudge;
import com.example.jarvis.eval.EvalArchiveWriter;
import com.example.jarvis.eval.EvalHistoryStore;
import com.example.jarvis.eval.EvalJudge;
import com.example.jarvis.eval.EvalModels;
import com.example.jarvis.eval.EvalReportRenderer;
import com.example.jarvis.eval.EvalRunSummary;
import com.example.jarvis.eval.EvalRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 评测薄壳（对应 docs/rag-design.md Phase1-#5 评估体系）。
 *
 * <p>编排/模型/渲染/阈值均在 {@code com.example.jarvis.eval} 包（EvalRunner / EvalModels /
 * EvalReportRenderer）；本类只负责 Spring 装配、报告落盘与基线断言。
 *
 * <p>两个独立用例，均按环境变量门控，默认 mvn test 不执行：
 * <ul>
 *   <li>{@code evalRetrieval}（RAG_EVAL=true）：检索层纯计算，零 token 成本。</li>
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

	/** 归档目录（相对项目根；评测中心 API 与 CI artifact 同源）。 */
	private static final Path HISTORY_DIR = Path.of("docs", "eval", "history");

	private final EvalRunner runner;
	private final EvalJudge judge;

	@Autowired
	RagEvalTest(KnowledgeService knowledgeService, RagProperties properties, Environment env) {
		this.runner = new EvalRunner(knowledgeService, properties);
		this.judge = createJudge(env);
	}

	/**
	 * LLM-as-judge（任务组5）：复用后端 agentscope.model.* 的 DeepSeek 端点；
	 * 未配置真实 key（占位 sk-demo-key）时返回 null，忠实度评测与断言自动跳过。
	 */
	private EvalJudge createJudge(Environment env) {
		String key = env.getProperty("agentscope.model.api-key");
		if (key == null || key.isBlank() || key.startsWith("sk-demo")) {
			return null;
		}
		String base = env.getProperty("agentscope.model.base-url", "https://api.deepseek.com/v1");
		String model = System.getenv("RAG_EVAL_JUDGE_MODEL");
		return new DeepSeekEvalJudge(base, key,
				model == null || model.isBlank() ? "deepseek-chat" : model.trim());
	}

	// ================================ 检索层 ================================

	@Test
	void evalRetrieval() throws IOException {
		EvalModels.RetrievalOutcome o = runner.runRetrieval();
		String report = EvalReportRenderer.renderRetrieval(o);

		// 趋势对比 + 归档：与上一次同 suite 归档 diff，随后写 docs/eval/history/<runId>/
		EvalHistoryStore store = new EvalHistoryStore(HISTORY_DIR);
		EvalRunSummary prev = store.latest("retrieval").orElse(null);
		report += prev != null
				? EvalReportRenderer.renderDiffTable(prev, o.metrics())
				: EvalReportRenderer.firstRunNote();
		EvalArchiveWriter writer = new EvalArchiveWriter(HISTORY_DIR);
		EvalRunSummary summary = writer.buildSummary("retrieval",
				Map.of("relevant", o.relevant().size(), "irrelevant", o.irrelevant().size()),
				o.metrics(), EvalReportRenderer.retrievalLatencySummary(o), runner.configSnapshot(null));
		summary.cases = runner.caseRecords(o); // 逐条明细：评测中心"用例明细表"数据源
		writer.write(summary, report);

		System.out.println(report);
		Path out = Path.of("target", "rag-eval-report.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report);

		assertTrue(o.recallAt4() >= EvalReportRenderer.MIN_RECALL_AT_4,
				"Recall@4=%.3f 低于基线 %.2f（报告见 %s）"
						.formatted(o.recallAt4(), EvalReportRenderer.MIN_RECALL_AT_4, out));
		assertTrue(o.mrr() >= EvalReportRenderer.MIN_MRR,
				"MRR=%.3f 低于基线 %.2f".formatted(o.mrr(), EvalReportRenderer.MIN_MRR));
		assertTrue(o.separation() >= EvalReportRenderer.MIN_SEPARATION,
				"相关-无关分数区分度=%.3f 低于基线 %.2f"
						.formatted(o.separation(), EvalReportRenderer.MIN_SEPARATION));
		assertTrue(o.wrongInjectRate() <= EvalReportRenderer.MAX_WRONG_INJECT_RATE,
				"误注入率=%.2f 高于上限 %.2f（考虑调高 rag.retrieval.inject-score）"
						.formatted(o.wrongInjectRate(), EvalReportRenderer.MAX_WRONG_INJECT_RATE));
		assertTrue(o.injectRecall() >= EvalReportRenderer.MIN_INJECT_RECALL,
				"注入召回=%.2f 低于基线 %.2f——inject-score 门槛在误杀真实知识问题"
						.formatted(o.injectRecall(), EvalReportRenderer.MIN_INJECT_RECALL));
	}

	// ================================ 生成层 ================================

	@Test
	@EnabledIfEnvironmentVariable(named = "RAG_EVAL_LLM", matches = "true",
			disabledReason = "生成层评测走真实 DeepSeek（消耗 token）：设置 RAG_EVAL_LLM=true 且后端运行中")
	void evalGeneration() throws Exception {
		String baseUrl = "http://localhost:8080";
		String token = resolveToken(baseUrl);
		EvalModels.GenerationOutcome g = runner.runGeneration(baseUrl, token, judge);
		String report = EvalReportRenderer.renderGeneration(g, judge != null);

		// 趋势对比 + 归档
		EvalHistoryStore store = new EvalHistoryStore(HISTORY_DIR);
		EvalRunSummary prev = store.latest("generation").orElse(null);
		report += prev != null
				? EvalReportRenderer.renderDiffTable(prev, g.metrics())
				: EvalReportRenderer.firstRunNote();
		EvalArchiveWriter writer = new EvalArchiveWriter(HISTORY_DIR);
		EvalRunSummary summary = writer.buildSummary("generation",
				Map.of("cases", g.cases()), g.metrics(),
				EvalReportRenderer.generationLatencySummary(g), runner.configSnapshot(judge));
		summary.cases = runner.genRecords(g);
		writer.write(summary, report);

		System.out.println(report);
		Files.writeString(Path.of("target", "rag-eval-report-generation.md"), report);

		assertTrue(g.answerRate() >= EvalReportRenderer.MIN_ANSWER_RATE,
				"答案要素命中率 %.2f 低于 0.80".formatted(g.answerRate()));
		assertTrue(g.citationRate() >= EvalReportRenderer.MIN_CITATION_RATE,
				"引用指向正确率 %.2f 低于 0.80".formatted(g.citationRate()));
		if (judge != null) {
			assertTrue(g.faithfulnessAvg() >= EvalReportRenderer.MIN_FAITHFULNESS_AVG,
					"忠实度均分 %.2f 低于 %.1f——答案可能夹带检索片段之外的依据"
							.formatted(g.faithfulnessAvg(), EvalReportRenderer.MIN_FAITHFULNESS_AVG));
			assertTrue(g.judgeCoverage() >= EvalReportRenderer.MIN_JUDGE_COVERAGE,
					"评分覆盖率 %.2f 低于 %.2f——judge 输出解析失败过多"
							.formatted(g.judgeCoverage(), EvalReportRenderer.MIN_JUDGE_COVERAGE));
		}
	}

	/**
	 * 生成层需 JWT（/api/agent/chat 已纳入鉴权）：优先 RAG_EVAL_TOKEN，
	 * 其次用 RAG_EVAL_USERNAME/RAG_EVAL_PASSWORD 登录换取；都没有则跳过本用例。
	 */
	private String resolveToken(String baseUrl) throws Exception {
		String token = System.getenv("RAG_EVAL_TOKEN");
		if (token != null && !token.isBlank()) {
			return token.trim();
		}
		String user = System.getenv("RAG_EVAL_USERNAME");
		String pass = System.getenv("RAG_EVAL_PASSWORD");
		Assumptions.assumeTrue(user != null && !user.isBlank() && pass != null && !pass.isBlank(),
				"生成层评测需认证：设置 RAG_EVAL_TOKEN，或 RAG_EVAL_USERNAME/RAG_EVAL_PASSWORD");
		ObjectMapper json = new ObjectMapper();
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/auth/login"))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(
						json.writeValueAsString(Map.of("username", user, "password", pass))))
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		Assumptions.assumeTrue(resp.statusCode() == 200,
				"评测账号登录失败（HTTP %d）：%s".formatted(resp.statusCode(), resp.body()));
		Map<String, Object> result = json.readValue(resp.body(), new TypeReference<>() { });
		return String.valueOf(result.get("token"));
	}
}
