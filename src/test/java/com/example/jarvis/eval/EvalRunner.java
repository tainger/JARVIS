package com.example.jarvis.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.jarvis.rag.KnowledgeService;
import com.example.jarvis.rag.RagProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

/**
 * 评测运行内核：加载标注集、执行检索层/生成层评测、计算指标。
 * 不做断言、不做渲染、不做归档——分别属于测试薄壳、Renderer、ArchiveWriter。
 */
public class EvalRunner {

	public static final int TOP_K = 4;

	private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

	private final KnowledgeService knowledgeService;
	private final RagProperties properties;
	private final ObjectMapper json = new ObjectMapper();

	public EvalRunner(KnowledgeService knowledgeService, RagProperties properties) {
		this.knowledgeService = knowledgeService;
		this.properties = properties;
	}

	public List<EvalModels.EvalCase> loadCases() throws IOException {
		return json.readValue(new ClassPathResource("rag-eval-cases.json").getInputStream(),
				new TypeReference<List<EvalModels.EvalCase>>() { });
	}

	// ================================ 检索层 ================================

	public EvalModels.RetrievalOutcome runRetrieval() throws IOException {
		List<EvalModels.EvalCase> cases = loadCases();
		List<EvalModels.CaseResult> relevant = new ArrayList<>();
		List<EvalModels.CaseResult> irrelevant = new ArrayList<>();
		Map<String, Long> latencies = new LinkedHashMap<>();

		for (EvalModels.EvalCase c : cases) {
			long start = System.nanoTime();
			List<KnowledgeService.SearchHit> hits = knowledgeService.search(c.question, TOP_K);
			long costMs = (System.nanoTime() - start) / 1_000_000;
			latencies.put(c.id, costMs);
			double top1 = hits.isEmpty() ? 0 : hits.get(0).score();
			int rank = c.irrelevant ? 0 : relevantRank(c, hits);
			boolean injected = knowledgeService.buildInjection(c.question, TOP_K) != null;
			EvalModels.CaseResult r = new EvalModels.CaseResult(c, rank, top1, hits,
					rank == 0 ? missDetail(c, hits) : null, costMs, injected);
			(c.irrelevant ? irrelevant : relevant).add(r);
		}

		long recallHits = relevant.stream().filter(r -> r.relevantRank() > 0).count();
		double recallAt4 = (double) recallHits / relevant.size();
		double mrr = relevant.stream().mapToDouble(r -> r.relevantRank() == 0 ? 0
				: 1.0 / r.relevantRank()).average().orElse(0);
		double sep = relevant.stream().mapToDouble(EvalModels.CaseResult::top1Score).average().orElse(0)
				- irrelevant.stream().mapToDouble(EvalModels.CaseResult::top1Score).average().orElse(0);
		long wrongInject = irrelevant.stream().filter(EvalModels.CaseResult::injected).count();
		double wrongInjectRate = (double) wrongInject / irrelevant.size();
		long marginalCount = relevant.stream().filter(r -> !r.injected()).count();
		double injectRecall = 1 - marginalCount / (double) relevant.size();

		return new EvalModels.RetrievalOutcome(relevant, irrelevant, recallAt4, mrr, sep,
				wrongInject, wrongInjectRate, injectRecall, latencies);
	}

	// ================================ 生成层 ================================

	public EvalModels.GenerationOutcome runGeneration(String baseUrl, String bearerToken, EvalJudge judge) throws Exception {
		List<EvalModels.EvalCase> cases = loadCases().stream()
				.filter(c -> !c.irrelevant && !c.expectAnswerKeywords.isEmpty())
				.toList();
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10)).build();
		List<EvalModels.GenRow> rows = new ArrayList<>();

		int answerHit = 0;
		int citationTotal = 0;
		int citationCorrect = 0;
		int judgedCount = 0;
		double faithSum = 0;
		int judgeable = 0;

		for (EvalModels.EvalCase c : cases) {
			long start = System.nanoTime();
			Map<String, Object> body = Map.of("message", c.question);
			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
					.uri(URI.create(baseUrl + "/api/agent/chat"))
					.header("Content-Type", "application/json")
					.timeout(Duration.ofSeconds(120));
			if (bearerToken != null && !bearerToken.isBlank()) {
				reqBuilder.header("Authorization", "Bearer " + bearerToken);
			}
			HttpRequest req = reqBuilder
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
					.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			long e2eMs = (System.nanoTime() - start) / 1_000_000;
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

			// 忠实度裁判：仅对被注入了检索片段的用例打分（工具路径回答无注入片段，不纳入覆盖率）
			Integer faithScore = null;
			String faithReason = null;
			if (!sources.isEmpty()) {
				judgeable++;
				if (judge != null) {
					String snippets = sources.stream()
							.map(s -> "[片段] %s :: %s".formatted(
									String.valueOf(s.get("documentTitle")),
									String.valueOf(s.getOrDefault("snippet", ""))))
							.reduce((a, b) -> a + "\n" + b).orElse("");
					EvalJudge.FaithVerdict v = judge.judge(answer, snippets);
					if (v != null) {
						faithScore = v.score();
						faithReason = v.reason();
						judgedCount++;
						faithSum += v.score();
					}
				}
			}
			rows.add(new EvalModels.GenRow(c, keywordsOk, citationNote, answer, e2eMs, faithScore, faithReason));
		}

		double answerRate = (double) answerHit / cases.size();
		double citationRate = citationTotal == 0 ? 1.0 : (double) citationCorrect / citationTotal;
		double faithAvg = judgedCount == 0 ? 0 : faithSum / judgedCount;
		double coverage = judgeable == 0 ? 0 : (double) judgedCount / judgeable;
		return new EvalModels.GenerationOutcome(rows, cases.size(), answerHit, answerRate,
				citationTotal, citationCorrect, citationRate, faithAvg, coverage);
	}

	// ================================ 命中判定 ================================

	/** 命中判定：文档匹配，且块内容含任一标注关键词（把"只答对文档"的水分挤掉） */
	boolean isRelevantHit(EvalModels.EvalCase c, KnowledgeService.SearchHit hit) {
		if (!hit.documentTitle().equals(c.expectDoc)) {
			return false;
		}
		String content = hit.content().toLowerCase();
		return c.expectChunkKeywords.stream().anyMatch(k -> content.contains(k.toLowerCase()));
	}

	int relevantRank(EvalModels.EvalCase c, List<KnowledgeService.SearchHit> hits) {
		for (int i = 0; i < hits.size(); i++) {
			if (isRelevantHit(c, hits.get(i))) {
				return i + 1;
			}
		}
		return 0;
	}

	String missDetail(EvalModels.EvalCase c, List<KnowledgeService.SearchHit> hits) {
		StringBuilder sb = new StringBuilder(c.id + "「" + c.question + "」Top%d: ".formatted(hits.size()));
		for (KnowledgeService.SearchHit h : hits) {
			sb.append("\n    %.3f %s#%d（%.40s…）".formatted(h.score(), h.documentTitle(),
					h.seq(), h.content().replace("\n", " ")));
		}
		return sb.toString();
	}

	// ================================ 逐条记录（归档 cases） ================================

	/** 检索层逐条记录：评测中心明细表数据源（含无关题，irrelevant=true 表示正确拒绝=未注入）。 */
	public List<Map<String, Object>> caseRecords(EvalModels.RetrievalOutcome o) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (EvalModels.CaseResult r : o.relevant()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.c().id);
			m.put("type", r.c().type);
			m.put("question", r.c().question);
			m.put("expectDoc", r.c().expectDoc);
			m.put("relevantRank", r.relevantRank());
			m.put("top1Score", r.top1Score());
			m.put("injected", r.injected());
			m.put("missDetail", r.missDetail());
			m.put("latencyMs", r.latencyMs());
			out.add(m);
		}
		for (EvalModels.CaseResult r : o.irrelevant()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.c().id);
			m.put("type", r.c().type);
			m.put("question", r.c().question);
			m.put("irrelevant", true);
			m.put("relevantRank", 0);
			m.put("top1Score", r.top1Score());
			m.put("injected", r.injected());
			m.put("latencyMs", r.latencyMs());
			out.add(m);
		}
		return out;
	}

	/** 生成层逐条记录：要素/引用/忠实度/端到端耗时。 */
	public List<Map<String, Object>> genRecords(EvalModels.GenerationOutcome g) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (EvalModels.GenRow r : g.rows()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.c().id);
			m.put("type", r.c().type);
			m.put("question", r.c().question);
			m.put("keywordsOk", r.keywordsOk());
			m.put("citationNote", r.citationNote());
			m.put("faithScore", r.faithScore());
			m.put("faithReason", r.faithReason());
			m.put("e2eMs", r.e2eMs());
			out.add(m);
		}
		return out;
	}

	// ================================ 配置快照 ================================

	public Map<String, Object> configSnapshot(EvalJudge judge) {
		Map<String, Object> cfg = new LinkedHashMap<>();
		RagProperties.Retrieval r = properties.getRetrieval();
		cfg.put("vectorWeight", 0.75);
		cfg.put("lexicalWeight", 0.25);
		cfg.put("topK", r.getTopK());
		cfg.put("minScore", r.getMinScore());
		cfg.put("injectScore", r.getInjectScore());
		RagProperties.Chunk ck = properties.getChunk();
		cfg.put("chunkMaxChars", ck.getMaxChars());
		cfg.put("chunkHardLimit", ck.getHardLimit());
		cfg.put("chunkOverlapChars", ck.getOverlapChars());
		cfg.put("embeddingModel", properties.getEmbedding().getModel());
		if (judge != null) {
			cfg.put("judgeModel", judge.model());
		}
		return cfg;
	}
}
