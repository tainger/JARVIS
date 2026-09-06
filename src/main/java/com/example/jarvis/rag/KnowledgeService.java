package com.example.jarvis.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.example.jarvis.mapper.KnowledgeMapper;
import com.example.jarvis.model.KnowledgeChunk;
import com.example.jarvis.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 知识库核心服务（检索核心独立，供多入口复用）：
 * - 导入：异步模式 — 同步分块+落库，异步 embedding，启动恢复中断任务
 * - 检索：混合评分 = 0.75 * 向量 cosine + 0.25 * 词面重合（中文 bigram + 英文词元），
 * 返回 Top-K 不做硬阈值截断（注入上下文时才按 minScore 过滤）
 * - 内存索引采用写时复制快照，导入/删除后原子替换，线程安全
 */
@Service
@DependsOnDatabaseInitialization
public class KnowledgeService {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

	private static final double VECTOR_WEIGHT = 0.75;

	private final KnowledgeMapper knowledgeMapper;

	private final OllamaEmbeddingClient embeddingClient;

	private final RagProperties properties;

	/**
	 * 延迟注入自身代理，用于在同类内调用 @Async 方法时走 Spring 代理。
	 * 避免 this.xxx() 绕过 AOP 导致 @Async 失效的问题。
	 */
	@Lazy
	private final KnowledgeService self;

	private final AtomicReference<List<IndexedChunk>> indexSnapshot =
			new AtomicReference<>(new CopyOnWriteArrayList<>());

	public record IndexedChunk(long chunkId, long documentId, String documentTitle,
			int seq, String content, float[] vector) {
	}

	public record SearchHit(long documentId, String documentTitle, int seq,
			String content, double score) {
	}

	public KnowledgeService(KnowledgeMapper knowledgeMapper,
			OllamaEmbeddingClient embeddingClient, RagProperties properties,
			@Lazy KnowledgeService self) {
		this.knowledgeMapper = knowledgeMapper;
		this.embeddingClient = embeddingClient;
		this.properties = properties;
		this.self = self;
		reloadIndex();
	}

	/** 向量化任务卡死超时时间（分钟），超过则重置为 processing 重新排队 */
	private static final int EMBEDDING_STUCK_TIMEOUT_MINUTES = 30;

	/**
	 * 定时兜底：每 60 秒扫描一次，先重置卡死任务，再串行处理 processing 状态的文档。
	 * 正常提交导入时由 @Async 立即触发，这里作为兜底和重启恢复。
	 */
	@Scheduled(fixedDelay = 60000)
	public void scheduledEmbeddingRecovery() {
		// 1. 先重置卡死的 embedding 任务（进程崩溃等场景）
		int reset = knowledgeMapper.resetStuckEmbedding(EMBEDDING_STUCK_TIMEOUT_MINUTES);
		if (reset > 0) {
			log.info("定时兜底：重置了 {} 个卡死的 embedding 任务", reset);
		}

		// 2. 串行处理 processing 状态的文档
		List<KnowledgeDocument> pending = knowledgeMapper.findByStatus("processing");
		if (pending.isEmpty()) {
			return;
		}
		log.info("定时兜底：发现 {} 个待处理文档，开始串行执行", pending.size());
		for (KnowledgeDocument doc : pending) {
			processEmbedding(doc.getId());
		}
	}

	// ---------- 导入 ----------

	/**
	 * 提交文档导入（同步部分）：清洗 → 分块 → 落库（status=processing）→ 触发异步 embedding。
	 * 立即返回文档对象，不等待 embedding 完成。
	 */
	public KnowledgeDocument submitImport(String title, String fileName, String content) {
		if (!StringUtils.hasText(content)) {
			throw new IllegalArgumentException("文档内容不能为空");
		}
		String trimmed = stripHtml(content.strip());
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("文档内容清洗后为空（可能全是 HTML 标签）");
		}
		List<String> chunks = splitIntoChunks(trimmed);

		KnowledgeDocument doc = new KnowledgeDocument();
		doc.setTitle(StringUtils.hasText(title) ? title.strip() : defaultTitle(fileName, trimmed));
		doc.setFileName(fileName);
		doc.setContent(trimmed);
		doc.setChunkCount(chunks.size());
		doc.setStatus("processing");
		doc.setChunkProgress(0);
		doc.setChunkTotal(chunks.size());
		knowledgeMapper.insertDocument(doc);

		for (int i = 0; i < chunks.size(); i++) {
			KnowledgeChunk chunk = new KnowledgeChunk();
			chunk.setDocumentId(doc.getId());
			chunk.setSeq(i);
			chunk.setContent(chunks.get(i));
			chunk.setEmbedding(null);
			chunk.setDim(0);
			knowledgeMapper.insertChunk(chunk);
		}

		log.info("文档 '{}' 已提交导入（{} 块），开始异步向量化", doc.getTitle(), chunks.size());
		self.processEmbedding(doc.getId());
		return doc;
	}

	/**
	 * 异步执行 embedding：逐块向量化 → 更新进度 → 完成时刷新索引。
	 * 标记为 @Async，不阻塞调用线程。
	 * 幂等：先删除已有 chunk 再重新分块+向量化，重启恢复时安全调用。
	 */
	@Async("knowledgeExecutor")
	public void processEmbedding(Long docId) {
		try {
			log.info("[线程: {}] 开始认领文档 docId={}", Thread.currentThread().getName(), docId);

			// CAS 认领：只有 status=processing 时才能抢到任务
			int claimed = knowledgeMapper.claimForEmbedding(docId);
			if (claimed == 0) {
				log.info("[线程: {}] 文档 docId={} 已被其他线程认领，跳过", Thread.currentThread().getName(), docId);
				return;
			}
			log.info("[线程: {}] 成功认领文档 docId={}，开始向量化", Thread.currentThread().getName(), docId);

			KnowledgeDocument doc = knowledgeMapper.findDocumentById(docId);
			if (doc == null) {
				log.warn("[线程: {}] 文档不存在：docId={}", Thread.currentThread().getName(), docId);
				return;
			}

			List<KnowledgeChunk> existingChunks = knowledgeMapper.findChunksByDocumentId(docId);
			List<String> chunkTexts;
			if (existingChunks.isEmpty() || existingChunks.get(0).getContent() == null) {
				String trimmed = stripHtml(doc.getContent().strip());
				chunkTexts = splitIntoChunks(trimmed);
				knowledgeMapper.deleteChunksByDocumentId(docId);
				for (int i = 0; i < chunkTexts.size(); i++) {
					KnowledgeChunk chunk = new KnowledgeChunk();
					chunk.setDocumentId(docId);
					chunk.setSeq(i);
					chunk.setContent(chunkTexts.get(i));
					chunk.setEmbedding(null);
					chunk.setDim(0);
					knowledgeMapper.insertChunk(chunk);
				}
			} else {
				chunkTexts = existingChunks.stream()
						.map(KnowledgeChunk::getContent)
						.toList();
			}

			int total = chunkTexts.size();
			for (int i = 0; i < total; i++) {
				float[] vector = embeddingClient.embedOne(chunkTexts.get(i));
				knowledgeMapper.updateChunkEmbedding(docId, i, toJson(vector), vector.length);
				knowledgeMapper.updateChunkProgress(docId, i + 1);
				if (i % 5 == 0) {
					log.info("文档 '{}' 向量化进度：{}/{}", doc.getTitle(), i + 1, total);
				}
			}

			knowledgeMapper.updateStatus(docId, "ready", null);
			reloadIndex();
			log.info("[线程: {}] 文档 '{}' 向量化完成（{} 块）", Thread.currentThread().getName(), doc.getTitle(), total);
		}
		catch (Exception e) {
			log.error("[线程: {}] 文档向量化失败：docId={}", Thread.currentThread().getName(), docId, e);
			knowledgeMapper.updateStatus(docId, "failed", truncateError(e.getMessage()));
		}
	}

	private String truncateError(String msg) {
		if (msg == null) {
			return null;
		}
		return msg.length() > 2000 ? msg.substring(0, 2000) + "..." : msg;
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

	public KnowledgeDocument getImportStatus(Long id) {
		return knowledgeMapper.findDocumentById(id);
	}

	public void retryImport(Long id) {
		KnowledgeDocument doc = knowledgeMapper.findDocumentById(id);
		if (doc == null) {
			throw new IllegalArgumentException("文档不存在：" + id);
		}
		if (!"failed".equals(doc.getStatus())) {
			throw new IllegalStateException("仅允许重试失败的文档");
		}
		knowledgeMapper.deleteChunksByDocumentId(id);
		knowledgeMapper.updateStatus(id, "processing", null);
		knowledgeMapper.updateChunkProgress(id, 0);
		log.info("重试导入文档：docId={}, title={}", id, doc.getTitle());
		processEmbedding(id);
	}

	/**
	 * 混合检索：查询向量化 → 内存全量评分（向量 cosine + 词面重合）→ Top-K。
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

		String q = query.strip();
		float[] queryVector = embeddingClient.embedOne(q);
		Set<String> queryTerms = tokenize(q);

		List<SearchHit> hits = new ArrayList<>();
		for (IndexedChunk chunk : index) {
			double vectorScore = cosine(queryVector, chunk.vector());
			double lexicalScore = lexicalScore(queryTerms, chunk.content());
			double score = VECTOR_WEIGHT * vectorScore + (1 - VECTOR_WEIGHT) * lexicalScore;
			hits.add(new SearchHit(chunk.documentId(), chunk.documentTitle(),
					chunk.seq(), chunk.content(), score));
		}
		return hits.stream()
				.sorted(Comparator.comparingDouble(SearchHit::score).reversed())
				.limit(k)
				.collect(Collectors.toList());
	}

	public RagInjection buildInjection(String query, Integer topK) {
		List<SearchHit> all = search(query, topK);
		if (all.isEmpty() || all.get(0).score() < properties.getRetrieval().getInjectScore()) {
			return null;
		}
		List<SearchHit> hits = all.stream()
				.filter(hit -> hit.score() >= properties.getRetrieval().getMinScore())
				.toList();
		if (hits.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			SearchHit hit = hits.get(i);
			sb.append(String.format("[%d] 来源：%s（片段 %d，相关度 %.3f）%n%s%n%n",
					i + 1, hit.documentTitle(), hit.seq(), hit.score(), hit.content()));
		}
		return new RagInjection(sb.toString().strip(), List.copyOf(hits));
	}

	public record RagInjection(String context, List<SearchHit> hits) {
	}

	public Map<String, Object> stats() {
		List<IndexedChunk> index = indexSnapshot.get();
		return Map.of(
				"documents", knowledgeMapper.findAllDocuments().size(),
				"indexedChunks", index.size(),
				"embeddingModel", embeddingClient.modelName());
	}

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

	// ---------- 分块（Markdown 标题感知 + 面包屑 + 重叠） ----------

	List<String> splitIntoChunks(String content) {
		int maxChars = properties.getChunk().getMaxChars();
		List<String> result = new ArrayList<>();

		Deque<String> path = new ArrayDeque<>();
		StringBuilder section = new StringBuilder();
		String breadcrumb = "";

		String[] lines = content.split("\n", -1);
		for (String line : lines) {
			Matcher m = HEADING.matcher(line.strip());
			if (m.matches()) {
				flushSection(result, breadcrumb, section.toString(), maxChars);
				section.setLength(0);
				int level = m.group(1).length();
				String text = m.group(2).strip();
				while (path.size() >= level) {
					path.pollLast();
				}
				path.addLast(text);
				breadcrumb = String.join(" > ", path);
			}
			else {
				section.append(line).append('\n');
			}
		}
		flushSection(result, breadcrumb, section.toString(), maxChars);
		return result.stream().filter(s -> !s.isBlank()).toList();
	}

	private void flushSection(List<String> result, String breadcrumb, String sectionText, int maxChars) {
		String body = sectionText.strip();
		if (body.isEmpty()) {
			return;
		}
		String prefix = breadcrumb.isEmpty() ? "" : breadcrumb + "\n";
		int budget = Math.max(120, maxChars - prefix.length());

		int overlap = properties.getChunk().getOverlapChars();
		StringBuilder current = new StringBuilder();
		for (String piece : piecesOf(body)) {
			if (current.length() > 0
					&& current.length() + piece.length() + 1 > budget) {
				result.add((prefix + current).strip());
				String tail = tailSentences(current.toString(), overlap);
				current = new StringBuilder(tail);
				if (!tail.isEmpty() && !tail.endsWith("\n")) {
					current.append('\n');
				}
			}
			if (current.length() > 0 && current.charAt(current.length() - 1) != '\n') {
				current.append('\n');
			}
			current.append(piece);
		}
		if (current.length() > 0) {
			result.add((prefix + current).strip());
		}
	}

	private List<String> piecesOf(String body) {
		RagProperties.Chunk cfg = properties.getChunk();
		List<String> pieces = new ArrayList<>();
		for (String paragraph : body.split("\\n\\s*\\n")) {
			String p = paragraph.strip();
			if (p.isEmpty()) {
				continue;
			}
			if (p.length() <= cfg.getHardLimit()) {
				pieces.add(p);
				continue;
			}
			StringBuilder piece = new StringBuilder();
			for (String sentence : p.split("(?<=[。！？；.!?\n])")) {
				if (piece.length() + sentence.length() > cfg.getHardLimit() && piece.length() > 0) {
					pieces.add(piece.toString().strip());
					piece.setLength(0);
				}
				piece.append(sentence);
			}
			if (piece.length() > 0) {
				pieces.add(piece.toString().strip());
			}
		}
		return pieces;
	}

	private String tailSentences(String text, int maxChars) {
		if (maxChars <= 0 || text.length() <= maxChars) {
			return maxChars <= 0 ? "" : text;
		}
		String tail = text.substring(text.length() - maxChars);
		int idx = tail.indexOf('。');
		if (idx < 0) {
			idx = tail.indexOf('\n');
		}
		return idx >= 0 && idx < tail.length() - 1 ? tail.substring(idx + 1) : tail;
	}

	private String defaultTitle(String fileName, String content) {
		if (StringUtils.hasText(fileName)) {
			return fileName;
		}
		String firstLine = content.split("\\R", 2)[0];
		return firstLine.length() > 50 ? firstLine.substring(0, 50) : firstLine;
	}

	// ---------- HTML 清洗 ----------

	String stripHtml(String input) {
		if (!input.contains("<")) {
			return input;
		}
		String out = input.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1\\s*>", " ");
		out = out.replaceAll("(?i)<br\\s*/?>", "\n");
		out = out.replaceAll("(?is)</(p|div|li|tr|h[1-6]|section|article|table|ul|ol|blockquote)>", "\n");
		out = out.replaceAll("(?is)<[^>]+>", "");
		out = out.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&lpar;", "(")
				.replace("&rpar;", ")");
		out = out.replaceAll("[ \\t\\u00a0]+", " ");
		out = out.replaceAll(" *\\n *", "\n");
		out = out.replaceAll("\\n{3,}", "\n\n");
		return out.strip();
	}

	// ---------- 混合评分 ----------

	double lexicalScore(Set<String> queryTerms, String text) {
		if (queryTerms.isEmpty() || text == null || text.isEmpty()) {
			return 0;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		int hit = 0;
		for (String term : queryTerms) {
			if (lower.contains(term)) {
				hit++;
			}
		}
		return (double) hit / queryTerms.size();
	}

	Set<String> tokenize(String query) {
		Set<String> terms = new LinkedHashSet<>();
		String normalized = query.toLowerCase(Locale.ROOT);
		StringBuilder ascii = new StringBuilder();
		StringBuilder cjk = new StringBuilder();

		flushAscii(ascii, terms);
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (Character.isLetterOrDigit(c) && c < 128) {
				ascii.append(c);
			}
			else {
				flushAscii(ascii, terms);
				if (isCjk(c)) {
					cjk.append(c);
				}
				else {
					flushCjk(cjk, terms);
				}
			}
		}
		flushAscii(ascii, terms);
		flushCjk(cjk, terms);
		return terms;
	}

	private void flushAscii(StringBuilder ascii, Set<String> terms) {
		if (ascii.length() >= 2) {
			terms.add(ascii.toString());
		}
		ascii.setLength(0);
	}

	private void flushCjk(StringBuilder cjk, Set<String> terms) {
		String s = cjk.toString();
		if (s.length() == 1) {
			terms.add(s);
		}
		else {
			for (int i = 0; i < s.length() - 1; i++) {
				terms.add(s.substring(i, i + 2));
			}
		}
		cjk.setLength(0);
	}

	private boolean isCjk(char c) {
		Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
		return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
				|| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
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
