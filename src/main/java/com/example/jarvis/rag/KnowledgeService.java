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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 知识库核心服务（检索核心独立，供多入口复用）：
 * - 导入：先向量化再入库（失败不留孤儿文档）；Markdown 标题感知分块 + 面包屑前缀 + 片段重叠
 * - 检索：混合评分 = 0.75 * 向量 cosine + 0.25 * 词面重合（中文 bigram + 英文词元），
 *         返回 Top-K 不做硬阈值截断（注入上下文时才按 minScore 过滤）
 * - 内存索引采用写时复制快照，导入/删除后原子替换，线程安全
 */
@Service
public class KnowledgeService {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

	/** Markdown 标题行，如 ## 报销制度 */
	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

	/** 混合评分中向量相似度的权重 */
	private static final double VECTOR_WEIGHT = 0.75;

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

	/** 一条检索命中（score 为混合评分：向量 + 词面） */
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
	 * 向量化在任何 DB 写入之前执行，失败时不会留下"有文档无向量"的孤儿记录。
	 *
	 * @return 落库后的文档（含 id 与 chunkCount）
	 */
	public KnowledgeDocument importDocument(String title, String fileName, String content) {
		if (!StringUtils.hasText(content)) {
			throw new IllegalArgumentException("文档内容不能为空");
		}
		// 清洗：简历/富文本导出的 md 常内嵌大量 HTML 标签（<div>/<img>/图标），
		// 直接分块会产生大量噪声块，必须先剥离
		String trimmed = stripHtml(content.strip());
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("文档内容清洗后为空（可能全是 HTML 标签）");
		}
		List<String> chunks = splitIntoChunks(trimmed);

		// 先向量化：失败直接抛异常，不产生半成品数据
		List<float[]> vectors = embeddingClient.embed(chunks);

		KnowledgeDocument doc = new KnowledgeDocument();
		doc.setTitle(StringUtils.hasText(title) ? title.strip() : defaultTitle(fileName, trimmed));
		doc.setFileName(fileName);
		doc.setContent(trimmed);
		doc.setChunkCount(chunks.size());
		knowledgeMapper.insertDocument(doc);

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
	 * 混合检索：查询向量化 → 内存全量评分（向量 cosine + 词面重合）→ Top-K。
	 * 不做硬阈值截断，保证"搜不到"与"分数低"是可区分的信息。
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

	/**
	 * 检索并组装"带引用编号的注入上下文"：片段按 [1..n] 编号（与返回的 hits 顺序一致，
	 * 供前端渲染来源卡片），无足够相关的命中返回 null。
	 * 这里做双阈值过滤：Top1 必须达到 injectScore 才注入（强相关才走入口 A）；
	 * 落在 [minScore, injectScore) 的多为"工具意图"等域内噪声，不注入，
	 * 由 agent 自主判断是否调用 knowledge_search 工具。
	 */
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

	/** 注入上下文 + 命中片段（编号一一对应，供回答引用来源展示） */
	public record RagInjection(String context, List<SearchHit> hits) {
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

	// ---------- 分块（Markdown 标题感知 + 面包屑 + 重叠） ----------

	/**
	 * 按结构分块：
	 * 1. 识别 Markdown 标题（# ~ ######），用标题栈维护"面包屑"路径；
	 * 2. 每个标题section内部按空行切段、顺序合并到 maxChars；
	 * 3. 超过 hardLimit 的段落按句子硬切；相邻块之间保留 overlapChars 重叠；
	 * 4. 每块正文前拼上面包屑（如 "JARVIS 团队手册 > 报销制度"），让块自带语义上下文。
	 * 纯文本（无标题）退化为按段落合并。
	 */
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
				// 遇到新标题：先落盘上一节，再更新标题栈
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

	/** 把一个 section 的正文切成长度合适的块并加入结果（含面包屑前缀） */
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
				// 新块以上一块的尾部开头，保持上下文连续
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

	/** 把正文拆成不超过 hardLimit 的"段/句级"碎片 */
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
			// 超长段落按句末标点/换行硬切
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

	/** 取文本末尾约 maxChars 的内容，并尽量从句子边界开始 */
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
		// 取第一行作为标题（截断到 50 字符）
		String firstLine = content.split("\\R", 2)[0];
		return firstLine.length() > 50 ? firstLine.substring(0, 50) : firstLine;
	}

	// ---------- HTML 清洗 ----------

	/**
	 * 剥离内嵌 HTML，只保留可见文本：
	 * script/style 整块删除；块级标签转行；其余标签删除；解码常见实体。
	 */
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

	/**
	 * 词面重合度（0~1）：查询分词后统计在文本中的命中比例。
	 * 中文按相邻双字（bigram），英文/数字按词元；能兜住"向量不敏感的精确词"
	 * （产品名、人名、型号、缩写等）。
	 */
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

	/**
	 * 查询分词：中文相邻双字 + 英文/数字词元，去重。
	 * 例："出差吃饭一天补多少钱" → [出差, 差吃, 吃饭, 饭一, 一天, 天补, 补多, 多少, 少钱]
	 * 例："bge-m3 是什么" → [bge, m3, 是什么]
	 */
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
