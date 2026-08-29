package com.example.jarvis.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 知识库配置。
 * embedding 依赖本地 Ollama 的 /api/embed 接口（如 bge-m3 模型）。
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

	private final Embedding embedding = new Embedding();

	private final Retrieval retrieval = new Retrieval();

	private final Chunk chunk = new Chunk();

	public Embedding getEmbedding() {
		return embedding;
	}

	public Retrieval getRetrieval() {
		return retrieval;
	}

	public Chunk getChunk() {
		return chunk;
	}

	public static class Embedding {

		/** Ollama 服务地址 */
		private String baseUrl = "http://127.0.0.1:11434";

		/** 向量模型名（需已 ollama pull） */
		private String model = "bge-m3";

		/** 单次请求超时（秒） */
		private int timeoutSeconds = 60;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public int getTimeoutSeconds() {
			return timeoutSeconds;
		}

		public void setTimeoutSeconds(int timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
		}

	}

	public static class Retrieval {

		/** 默认返回的命中片段数 */
		private int topK = 4;

		/** 相似度低于该值的结果丢弃（cosine，范围约 [-1,1]） */
		private double minScore = 0.3;

		public int getTopK() {
			return topK;
		}

		public void setTopK(int topK) {
			this.topK = topK;
		}

		public double getMinScore() {
			return minScore;
		}

		public void setMinScore(double minScore) {
			this.minScore = minScore;
		}

	}

	public static class Chunk {

		/** 目标分块大小（字符） */
		private int maxChars = 800;

		/** 超长段落强制切分的硬上限（字符） */
		private int hardLimit = 1200;

		public int getMaxChars() {
			return maxChars;
		}

		public void setMaxChars(int maxChars) {
			this.maxChars = maxChars;
		}

		public int getHardLimit() {
			return hardLimit;
		}

		public void setHardLimit(int hardLimit) {
			this.hardLimit = hardLimit;
		}

	}

}
