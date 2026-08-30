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
		private int timeoutSeconds = 120;

		/** 模型在内存中的保活时长（Ollama keep_alive），避免空闲卸载后冷加载超时 */
		private String keepAlive = "60m";

		/** 单次 HTTP 请求的最大输入条数。CPU 推理约 6s/条，批太大会撞超时墙 */
		private int batchSize = 4;

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

		public String getKeepAlive() {
			return keepAlive;
		}

		public void setKeepAlive(String keepAlive) {
			this.keepAlive = keepAlive;
		}

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

	}

	public static class Retrieval {

		/** 默认返回的命中片段数 */
		private int topK = 4;

		/** 注入对话上下文的混合评分下限（0.75*cosine + 0.25*词面；bge-m3 相似带较窄） */
		private double minScore = 0.25;

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

		/** 目标分块大小（字符）。块太大语义稀释，检索区分度下降 */
		private int maxChars = 500;

		/** 超长段落强制切分的硬上限（字符） */
		private int hardLimit = 800;

		/** 相邻块之间的重叠字符数，避免关键信息恰好落在切块边界 */
		private int overlapChars = 80;

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

		public int getOverlapChars() {
			return overlapChars;
		}

		public void setOverlapChars(int overlapChars) {
			this.overlapChars = overlapChars;
		}

	}

}
