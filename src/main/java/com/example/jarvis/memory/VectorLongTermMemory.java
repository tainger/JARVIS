package com.example.jarvis.memory;

import java.util.ArrayList;
import java.util.List;

import com.example.jarvis.mapper.UserMemoryMapper;
import com.example.jarvis.model.UserMemory;
import com.example.jarvis.rag.OllamaEmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 向量长期记忆：实现 AgentScope {@link LongTermMemory} 接口。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>record(): LLM 从对话轮次提取用户事实 → bge-m3 向量化 → 去重 → 写入 user_memory 表</li>
 *   <li>retrieve(): query 向量化 → 遍历当前用户记忆算 cosine → 返回 Top-K 注入系统提示</li>
 *   <li>所有记忆按 user_id 严格隔离，从根本上杜绝跨用户串扰</li>
 *   <li>异步记录（record 返回 Mono，由框架异步执行，不阻塞响应）</li>
 * </ul>
 */
public class VectorLongTermMemory implements LongTermMemory {

	private static final Logger log = LoggerFactory.getLogger(VectorLongTermMemory.class);

	/** 检索返回的最大记忆条数 */
	private static final int TOP_K = 3;

	/** 去重相似度阈值：cosine > 0.85 视为同一事实 */
	private static final double DEDUP_THRESHOLD = 0.85;

	/** 检索最低相似度阈值：低于此值不注入 */
	private static final double MIN_SCORE = 0.3;

	private final Long userId;

	private final UserMemoryMapper userMemoryMapper;

	private final OllamaEmbeddingClient embeddingClient;

	private final OpenAIChatModel chatModel;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public VectorLongTermMemory(Long userId, UserMemoryMapper userMemoryMapper,
			OllamaEmbeddingClient embeddingClient, OpenAIChatModel chatModel) {
		this.userId = userId;
		this.userMemoryMapper = userMemoryMapper;
		this.embeddingClient = embeddingClient;
		this.chatModel = chatModel;
	}

	@Override
	public Mono<Void> record(List<Msg> msgs) {
		return Mono.fromRunnable(() -> {
			try {
				doRecord(msgs);
			}
			catch (Exception e) {
				log.warn("长期记忆记录失败（不影响对话）：userId={}, error={}", userId, e.getMessage());
			}
		});
	}

	@Override
	public Mono<String> retrieve(Msg msg) {
		return Mono.fromCallable(() -> doRetrieve(msg))
				.onErrorResume(e -> {
					log.warn("长期记忆检索失败（不影响对话）：userId={}, error={}", userId, e.getMessage());
					return Mono.just("");
				});
	}

	// === record 实现 ===

	private void doRecord(List<Msg> msgs) {
		if (msgs == null || msgs.isEmpty()) {
			return;
		}
		// 1. 提取对话文本
		String dialogue = extractDialogue(msgs);
		if (dialogue.isBlank()) {
			return;
		}
		// 2. LLM 提取事实
		List<ExtractedFact> facts = extractFacts(dialogue);
		if (facts.isEmpty()) {
			return;
		}
		log.info("长期记忆提取：userId={}, 提取事实 {} 条", userId, facts.size());
		// 3. 逐条向量化 + 去重 + 入库
		for (ExtractedFact fact : facts) {
			try {
				float[] vector = embeddingClient.embedOne(fact.content());
				String embeddingJson = toJson(vector);
				// 去重检查
				if (isDuplicate(vector)) {
					log.debug("长期记忆去重：事实已存在，更新 last_accessed_at：{}", fact.content());
					continue;
				}
				UserMemory memory = new UserMemory(userId, fact.content(), fact.type());
				memory.setEmbedding(embeddingJson);
				memory.setDim(vector.length);
				userMemoryMapper.insert(memory);
				log.debug("长期记忆入库：type={}, content={}", fact.type(), fact.content());
			}
			catch (Exception e) {
				log.warn("单条记忆向量化/入库失败：content={}, error={}", fact.content(), e.getMessage());
			}
		}
	}

	/** 从消息列表提取 user + assistant 对话文本 */
	private String extractDialogue(List<Msg> msgs) {
		StringBuilder sb = new StringBuilder();
		for (Msg msg : msgs) {
			if (msg.getRole() == MsgRole.USER) {
				sb.append("用户：").append(msg.getTextContent()).append("\n");
			}
			else if (msg.getRole() == MsgRole.ASSISTANT) {
				sb.append("助手：").append(msg.getTextContent()).append("\n");
			}
		}
		return sb.toString().strip();
	}

	/** 调用 LLM 从对话中提取用户事实 */
	private List<ExtractedFact> extractFacts(String dialogue) {
		String prompt = """
				请从以下对话中提取用户的关键信息，以 JSON 数组格式返回。
				每条信息包含 content（事实描述）和 type（类型：fact=客观事实, preference=偏好, goal=目标）。
				只提取明确陈述的信息，不要猜测。没有相关信息时返回空数组 []。

				对话：
				%s

				只返回 JSON 数组，不要其他文字。示例：
				[{"content":"用户叫张三","type":"fact"},{"content":"用户偏好中文回答","type":"preference"}]
				""".formatted(dialogue);

		try {
			Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
			List<ChatResponse> responses = chatModel.stream(List.of(userMsg), null, null)
					.collectList().block();
			StringBuilder text = new StringBuilder();
			for (ChatResponse r : responses) {
				for (ContentBlock block : r.getContent()) {
					if (block instanceof TextBlock tb) {
						text.append(tb.getText());
					}
				}
			}
			return parseFacts(text.toString());
		}
		catch (Exception e) {
			log.warn("LLM 事实提取失败：{}", e.getMessage());
			return List.of();
		}
	}

	/** 解析 LLM 返回的 JSON 事实列表 */
	private List<ExtractedFact> parseFacts(String llmOutput) {
		List<ExtractedFact> facts = new ArrayList<>();
		try {
			// 兼容 LLM 可能包裹 ```json ``` 的情况
			String json = llmOutput.strip();
			if (json.startsWith("```")) {
				int firstNewline = json.indexOf('\n');
				if (firstNewline > 0) {
					json = json.substring(firstNewline + 1);
				}
				if (json.endsWith("```")) {
					json = json.substring(0, json.length() - 3);
				}
				json = json.strip();
			}
			JsonNode root = objectMapper.readTree(json);
			if (root.isArray()) {
				for (JsonNode node : root) {
					String content = node.path("content").asText("").strip();
					String type = node.path("type").asText("fact").strip();
					if (!content.isBlank()) {
						facts.add(new ExtractedFact(content, type));
					}
				}
			}
		}
		catch (Exception e) {
			log.warn("解析 LLM 事实 JSON 失败：output={}, error={}", llmOutput, e.getMessage());
		}
		return facts;
	}

	/** 去重检查：与已有记忆算 cosine，超过阈值则更新 last_accessed_at */
	private boolean isDuplicate(float[] newVector) {
		try {
			List<UserMemory> existing = userMemoryMapper.findByUserId(userId);
			for (UserMemory mem : existing) {
				if (mem.getEmbedding() == null || mem.getEmbedding().isBlank()) {
					continue;
				}
				float[] existingVector = fromJson(mem.getEmbedding());
				double score = cosineSimilarity(newVector, existingVector);
				if (score > DEDUP_THRESHOLD) {
					userMemoryMapper.updateLastAccessedAt(mem.getId());
					return true;
				}
			}
		}
		catch (Exception e) {
			log.warn("去重检查失败：{}", e.getMessage());
		}
		return false;
	}

	// === retrieve 实现 ===

	private String doRetrieve(Msg msg) {
		if (msg == null || msg.getTextContent() == null || msg.getTextContent().isBlank()) {
			return "";
		}
		try {
			float[] queryVector = embeddingClient.embedOne(msg.getTextContent());
			List<UserMemory> allMemories = userMemoryMapper.findByUserId(userId);
			// 计算相似度并排序
			List<ScoredMemory> scored = new ArrayList<>();
			for (UserMemory mem : allMemories) {
				if (mem.getEmbedding() == null || mem.getEmbedding().isBlank()) {
					continue;
				}
				float[] memVector = fromJson(mem.getEmbedding());
				double score = cosineSimilarity(queryVector, memVector);
				if (score >= MIN_SCORE) {
					scored.add(new ScoredMemory(mem, score));
				}
			}
			scored.sort((a, b) -> Double.compare(b.score(), a.score()));
			// 更新命中记忆的 last_accessed_at
			List<ScoredMemory> topK = scored.size() > TOP_K ? scored.subList(0, TOP_K) : scored;
			for (ScoredMemory sm : topK) {
				userMemoryMapper.updateLastAccessedAt(sm.memory().getId());
			}
			if (topK.isEmpty()) {
				return "";
			}
			// 格式化为系统提示注入文本
			StringBuilder sb = new StringBuilder("【已知用户信息】\n");
			for (int i = 0; i < topK.size(); i++) {
				ScoredMemory sm = topK.get(i);
				sb.append("- ").append(sm.memory().getContent());
				if (i < topK.size() - 1) {
					sb.append("\n");
				}
			}
			return sb.toString();
		}
		catch (Exception e) {
			log.warn("长期记忆检索异常：{}", e.getMessage());
			return "";
		}
	}

	// === 向量工具方法 ===

	private double cosineSimilarity(float[] a, float[] b) {
		if (a == null || b == null || a.length != b.length) {
			return 0.0;
		}
		double dot = 0.0, normA = 0.0, normB = 0.0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		if (normA == 0 || normB == 0) {
			return 0.0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	private String toJson(float[] vector) {
		try {
			return objectMapper.writeValueAsString(vector);
		}
		catch (Exception e) {
			throw new IllegalStateException("向量序列化失败", e);
		}
	}

	private float[] fromJson(String json) {
		try {
			JsonNode node = objectMapper.readTree(json);
			float[] vector = new float[node.size()];
			for (int i = 0; i < node.size(); i++) {
				vector[i] = node.get(i).floatValue();
			}
			return vector;
		}
		catch (Exception e) {
			throw new IllegalStateException("向量反序列化失败", e);
		}
	}

	// === 内部记录类型 ===

	private record ExtractedFact(String content, String type) {
	}

	private record ScoredMemory(UserMemory memory, double score) {
	}

}
