package com.example.jarvis.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.jarvis.mapper.KnowledgeMapper;
import com.example.jarvis.model.KnowledgeChunk;
import com.example.jarvis.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 知识库核心服务（检索核心独立，供多入口复用）：
 * - 导入：段落感知分块 → Ollama 批量向量化 → 持久化到 H2
 * - 检索：内存中全量 cosine 相似度 Top-K（文档量 &lt; 5000 块时性能可接受）
 * - 内存索引采用写时复制快照，导入/删除后原子替换，线程安全
 */
@Service
public class KnowledgeService {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

	private final KnowledgeMapper knowledgeMapper;

	private final OllamaEmbeddingClient embeddingClient;

	private final RagProperties properties;

	/** 内存向量索引快照；查询时整体读取，写操作时整体替换 */
	private final AtomicReference<List<IndexedChunk>> indexSnapshot =
			new AtomicReference<>(new CopyOnWriteArrayList<>());

	/** 内存索引中的一条向量记录 */
	public record IndexedChunk(long chunkId, long documentId, String documentTitle,
			int seq, String content, float[] vector) {
	}

	/** 一条检索命中 */
	public record SearchHit(long documentId, String documentTitle, int seq,
			String content, double score) {
	}

	public KnowledgeService(KnowledgeMapper knowledgeMapper,
			OllamaEmbeddingClient embeddingClient, RagProperties properties) {
		this.knowledgeMapper = knowledgeMapper;
		this.embeddingClient = embeddingClient;
		this.properties = properties;
		reloadIndex();
	}

	// ---------- 导入 ----------

	/**
	 * 导入一篇文档：分块 → 向量化 → 落库。
	 *
	 * @return 落库后的文档（含 id 与 chunkCount）
	 */
	public KnowledgeDocument importDocument(String title, String fileName, String content) {
		if (!StringUtils.hasText(content)) {
			throw new IllegalArgumentException("文档内容不能为空");
		}
		String trimmed = content.strip();
		List<String> chunks = splitIntoChunks(trimmed);

		KnowledgeDocument doc = new KnowledgeDocument();
		doc.setTitle(StringUtils.hasText(title) ? title.strip() : defaultTitle(fileName, trimmed));
		doc.setFileName(fileName);
		doc.setContent(trimmed);
		doc.setChunkCount(chunks.size());
		knowledgeMapper.insertDocument(doc);

		// 批量向量化
		List<float[]> vectors = embeddingClient.embed(chunks);

		for (int i = 0; i < chunks.size(); i++) {
			KnowledgeChunk chunk = new KnowledgeChunk();
			chunk.setDocumentId(doc.getId());
			chunk.setSeq(i);
			chunk.setContent(chunks.get(i));
			chunk.setEmbedding(toJson(vectors.get(i)));
			chunk.setDim(vectors.get(i).length);
			knowledgeMapper.insertChunk(chunk);
		}

		log.info("知识库导入文档 '{}'（{} 字符，{} 块）", doc.getTitle(), trimmed.length(), chunks.size());
		reloadIndex();
		return doc;
	}

	// ---------- 删除 ----------

	public void deleteDocument(Long id) {
		if (knowledgeMapper.findDocumentById(id) == null) {
			throw new IllegalArgumentException("文档不存在：" + id);
		}
		knowledgeMapper.deleteChunksByDocumentId(id);
		knowledgeMapper.deleteDocument(id);
		log.info("知识库删除文档 id={}", id);
		reloadIndex();
	}

	// ---------- 查询 ----------

	public List<KnowledgeDocument> listDocuments() {
		return knowledgeMapper.findAllDocuments();
	}

	public KnowledgeDocument getDocument(Long id) {
		KnowledgeDocument doc = knowledgeMapper.findDocumentById(id);
		if (doc == null) {
			throw new IllegalArgumentException("文档不存在：" + id);
		}
		return doc;
	}

	/**
	 * 向量检索：查询向量化 → 内存全量 cosine → Top-K。
	 */
	public List<SearchHit> search(String query, Integer topK) {
		if (!StringUtils.hasText(query)) {
			return List.of();
		}
		List<IndexedChunk> index = indexSnapshot.get();
		if (index.isEmpty()) {
			return List.of();
		}
		int k = topK != null && topK > 0 ? Math.min(topK, 20) : properties.getRetrieval().getTopK();

		float[] queryVector = embeddingClient.embedOne(query.strip());
		List<SearchHit> hits = new ArrayList<>();
		for (IndexedChunk chunk : index) {
			double score = cosine(queryVector, chunk.vector());
			if (score >= properties.getRetrieval().getMinScore()) {
				hits.add(new SearchHit(chunk.documentId(), chunk.documentTitle(),
						chunk.seq(), chunk.content(), score));
			}
		}
		return hits.stream()
				.sorted(Comparator.comparingDouble(SearchHit::score).reversed())
				.limit(k)
				.collect(Collectors.toList());
	}

	/**
	 * 检索并格式化为可注入 prompt 的上下文文本；无命中返回 null。
	 */
	public String buildContext(String query, Integer topK) {
		List<SearchHit> hits = search(query, topK);
		if (hits.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			SearchHit hit = hits.get(i);
			sb.append(String.format("[%d] 来源：%s（片段 %d，相似度 %.3f）%n%s%n%n",
					i + 1, hit.documentTitle(), hit.seq(), hit.score(), hit.content()));
		}
		return sb.toString().strip();
	}

	/**
	 * 知识库统计信息（供健康检查 / 前端展示）。
	 */
	public Map<String, Object> stats() {
		List<IndexedChunk> index = indexSnapshot.get();
		return Map.of(
				"documents", knowledgeMapper.findAllDocuments().size(),
				"indexedChunks", index.size(),
				"embeddingModel", embeddingClient.modelName());
	}

	/** 重建内存索引（启动时 / 文档变更后调用） */
	public synchronized void reloadIndex() {
		Map<Long, String> titles = knowledgeMapper.findAllDocuments().stream()
				.collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getTitle));
		List<IndexedChunk> fresh = knowledgeMapper.findAllChunks().stream()
				.map(chunk -> new IndexedChunk(chunk.getId(), chunk.getDocumentId(),
						titles.getOrDefault(chunk.getDocumentId(), "文档#" + chunk.getDocumentId()),
						chunk.getSeq(), chunk.getContent(), parseVector(chunk.getEmbedding())))
				.filter(chunk -> chunk.vector() != null)
				.collect(Collectors.toCollection(CopyOnWriteArrayList::new));
		indexSnapshot.set(fresh);
		log.info("知识库内存索引已加载：{} 个向量块", fresh.size());
	}

	// ---------- 分块 ----------

	/**
	 * 段落感知分块：按空行切段，顺序合并到接近 maxChars；
	 * 超过 hardLimit 的单段按句号/换行硬切。
	 */
	List<String> splitIntoChunks(String content) {
		List<String> merged = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String paragraph : content.split("\\n\\s*\\n")) {
			String p = paragraph.strip();
			if (p.isEmpty()) {
				continue;
			}
			for (String piece : hardSplit(p)) {
				if (current.length() > 0
						&& current.length() + piece.length() + 1 > properties.getChunk().getMaxChars()) {
					merged.add(current.toString());
					current = new StringBuilder();
				}
				if (current.length() > 0) {
					current.append('\n');
				}
				current.append(piece);
			}
		}
		if (current.length() > 0) {
			merged.add(current.toString());
		}
		return merged;
	}

	/** 超长段落按句末标点/换行硬切到 hardLimit 以内 */
	private List<String> hardSplit(String paragraph) {
		int hardLimit = properties.getChunk().getHardLimit();
		if (paragraph.length() <= hardLimit) {
			return List.of(paragraph);
		}
		List<String> pieces = new ArrayList<>();
		StringBuilder piece = new StringBuilder();
		for (String sentence : paragraph.split("(?<=[。！？；.!?\n])")) {
			if (piece.length() + sentence.length() > hardLimit && piece.length() > 0) {
				pieces.add(piece.toString());
				piece = new StringBuilder();
			}
			piece.append(sentence);
		}
		if (piece.length() > 0) {
			pieces.add(piece.toString());
		}
		return pieces;
	}

	private String defaultTitle(String fileName, String content) {
		if (StringUtils.hasText(fileName)) {
			return fileName;
		}
		// 取第一行作为标题（截断到 50 字符）
		String firstLine = content.split("\\R", 2)[0];
		return firstLine.length() > 50 ? firstLine.substring(0, 50) : firstLine;
	}

	// ---------- 向量工具 ----------

	private static double cosine(float[] a, float[] b) {
		int n = Math.min(a.length, b.length);
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < n; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0 || normB == 0) {
			return 0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	private String toJson(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 8);
		sb.append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		sb.append(']');
		return sb.toString();
	}

	private float[] parseVector(String json) {
		if (!StringUtils.hasText(json)) {
			return null;
		}
		try {
			String[] parts = json.substring(1, json.length() - 1).split(",");
			float[] vector = new float[parts.length];
			for (int i = 0; i < parts.length; i++) {
				vector[i] = Float.parseFloat(parts[i].trim());
			}
			return vector;
		}
		catch (Exception e) {
			log.warn("向量解析失败，忽略该块：{}", e.getMessage());
			return null;
		}
	}

}
