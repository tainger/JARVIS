package com.example.jarvis.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM-as-judge 的 DeepSeek 实现（任务组5）：直连 /chat/completions，
 * 强制输出 {"score":1-5,"reason":"..."}；解析失败重试 1 次，仍失败返回 null（不计入均分）。
 * 只在测试侧使用（RAG_EVAL_LLM 生成层评测），key 经 agentscope.model.api-key 传入。
 */
public class DeepSeekEvalJudge implements EvalJudge {

	/** 模型输出里截取 JSON 对象用。 */
	private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final ObjectMapper json = new ObjectMapper();

	public DeepSeekEvalJudge(String baseUrl, String apiKey, String model) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.apiKey = apiKey;
		this.model = model;
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public FaithVerdict judge(String answer, String snippets) throws Exception {
		FaithVerdict first = ask(answer, snippets);
		return first != null ? first : ask(answer, snippets); // 解析失败重试 1 次
	}

	private FaithVerdict ask(String answer, String snippets) throws Exception {
		String body = json.writeValueAsString(Map.of(
				"model", model,
				"temperature", 0,
				"max_tokens", 200,
				"messages", List.of(
						Map.of("role", "system", "content", """
								你是 RAG 答案忠实度裁判。判断"回答"是否仅由"检索片段"支撑（无片段外信息、无与片段矛盾）。
								只输出 JSON：{"score":1-5,"reason":"一句话中文理由"}。
								5=完全支撑；4=基本支撑(略有措辞泛化)；3=部分无依据；2=大部分无依据；1=与片段矛盾。"""),
						Map.of("role", "user", "content", "检索片段：\n%s\n\n回答：\n%s"
								.formatted(snippets, answer)))));
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/chat/completions"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.timeout(Duration.ofSeconds(60))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			return null;
		}
		return parse(resp.body());
	}

	/** 从 chat/completions 响应提取裁决；任何解析失败返回 null。 */
	private FaithVerdict parse(String responseBody) {
		try {
			JsonNode root = json.readTree(responseBody);
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			Matcher m = JSON_BLOCK.matcher(content);
			if (!m.find()) {
				return null;
			}
			JsonNode v = json.readTree(m.group());
			int score = v.path("score").asInt(0);
			if (score < 1 || score > 5) {
				return null;
			}
			return new FaithVerdict(score, v.path("reason").asText(""));
		}
		catch (Exception e) {
			return null;
		}
	}
}
