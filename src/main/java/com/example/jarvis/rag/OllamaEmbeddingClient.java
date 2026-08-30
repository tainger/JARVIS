package com.example.jarvis.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Ollama 向量化客户端：HTTP 直调 /api/embed，批量向量化，零新增依赖。
 * Java HttpClient 默认不走系统代理，可直连本机 Ollama。
 */
@Component
@EnableConfigurationProperties(RagProperties.class)
public class OllamaEmbeddingClient {

	private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

	private final RagProperties properties;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public OllamaEmbeddingClient(RagProperties properties) {
		this.properties = properties;
	}

	/**
	 * 批量向量化一组文本，返回与输入顺序一致的向量列表。
	 * 内部按 batchSize 拆分为多个小批次请求：
	 * - CPU 推理约 6s/条，大批次会撞超时墙；
	 * - 小批次让每次请求耗时可控，失败也能定位到具体批次。
	 * 每个请求携带 keep_alive 让模型常驻内存，避免空闲卸载后冷加载超时。
	 */
	public List<float[]> embed(List<String> texts) {
		if (texts.isEmpty()) {
			return List.of();
		}
		RagProperties.Embedding cfg = properties.getEmbedding();
		int batchSize = Math.max(1, cfg.getBatchSize());
		List<float[]> result = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += batchSize) {
			List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
			result.addAll(embedBatch(batch, cfg));
			if (from + batchSize < texts.size()) {
				log.debug("Ollama embedding 进度：{}/{}", result.size(), texts.size());
			}
		}
		return result;
	}

	private List<float[]> embedBatch(List<String> batch, RagProperties.Embedding cfg) {
		try {
			String body = objectMapper.writeValueAsString(Map.of(
					"model", cfg.getModel(),
					"input", batch,
					"keep_alive", cfg.getKeepAlive()));
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(cfg.getBaseUrl() + "/api/embed"))
					.timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response =
					httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException(
						"Ollama embedding 请求失败：HTTP " + response.statusCode() + " " + truncate(response.body()));
			}
			Map<?, ?> result = objectMapper.readValue(response.body(), Map.class);
			Object embeddings = result.get("embeddings");
			if (!(embeddings instanceof List<?> list) || list.size() != batch.size()) {
				throw new IllegalStateException("Ollama embedding 响应缺少 embeddings 字段或条数不匹配");
			}
			return list.stream()
					.map(item -> toVector((List<?>) item))
					.toList();
		}
		catch (HttpTimeoutException e) {
			throw new IllegalStateException(
					"Ollama 向量化请求超时（>%ds）。通常是模型冷加载过慢或 Ollama 忙碌，"
							+ "可增大 rag.embedding.timeout-seconds 或调小 rag.embedding.batch-size 后重试：%s"
									.formatted(cfg.getTimeoutSeconds(), e.getMessage()), e);
		}
		catch (IOException e) {
			throw new IllegalStateException(
					"无法连接 Ollama（" + cfg.getBaseUrl() + "），请确认已运行 ollama serve：" + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("向量化请求被中断", e);
		}
	}

	public float[] embedOne(String text) {
		return embed(List.of(text)).get(0);
	}

	public String modelName() {
		return properties.getEmbedding().getModel();
	}

	private float[] toVector(List<?> raw) {
		float[] vector = new float[raw.size()];
		for (int i = 0; i < raw.size(); i++) {
			vector[i] = ((Number) raw.get(i)).floatValue();
		}
		return vector;
	}

	private String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}

}
