package com.example.jarvis.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.jarvis.config.AgentFactory;
import com.example.jarvis.config.AgentScopeConfig;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.dto.ChatSource;
import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.model.Conversation;
import com.example.jarvis.rag.KnowledgeService;
import com.example.jarvis.service.ConversationService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

	private static final Logger log = LoggerFactory.getLogger(AgentController.class);

	private final AgentFactory agentFactory;

	private final ConversationService conversationService;

	private final MessageMapper messageMapper;

	private final KnowledgeService knowledgeService;

	private final AgentScopeConfig agentScopeConfig;

	public AgentController(AgentFactory agentFactory, ConversationService conversationService,
			MessageMapper messageMapper, KnowledgeService knowledgeService,
			AgentScopeConfig agentScopeConfig) {
		this.agentFactory = agentFactory;
		this.conversationService = conversationService;
		this.messageMapper = messageMapper;
		this.knowledgeService = knowledgeService;
		this.agentScopeConfig = agentScopeConfig;
	}

	@PostMapping("/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		Long userId = currentUserId();
		Conversation conversation = resolveConversation(userId, request.conversationId(), request.message());
		ReActAgent agent = agentFactory.create(userId, conversation.getId(), buildSystemPrompt(request.mode()));
		AugmentedInput input = augmentWithKnowledge(request.message());
		Msg response = agent.call(Msg.builder().textContent(input.message()).build()).block();
		// 持久化对话
		persistMessages(conversation.getId(), userId, request.message(), response.getTextContent());
		conversationService.touch(conversation.getId());
		return new ChatResponse(response.getTextContent(), input.sources());
	}

	/**
	 * Streaming chat endpoint (Server-Sent Events).
	 * 先发 conversation 事件（会话ID），再发 sources 事件（知识库引用），随后逐帧发送文本增量；
	 * the stream ends when the agent emits its last event.
	 */
	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chatStream(@RequestBody ChatRequest request) {
		SseEmitter emitter = new SseEmitter(0L); // no timeout, stream may be long

		Long userId = currentUserId();
		// 1. 解析/创建会话
		Conversation conversation = resolveConversation(userId, request.conversationId(), request.message());

		// 2. 发送会话ID给前端（首次请求前端需要保存）
		try {
			emitter.send(SseEmitter.event()
					.name("conversation")
					.data(Map.of("conversationId", conversation.getId())));
		}
		catch (IOException e) {
			sendErrorAndComplete(emitter, e);
			return emitter;
		}

		// 3. RAG 知识库增强
		AugmentedInput input = augmentWithKnowledge(request.message());
		if (!input.sources().isEmpty()) {
			try {
				emitter.send(SseEmitter.event().name("sources").data(input.sources()));
			}
			catch (IOException e) {
				sendErrorAndComplete(emitter, e);
				return emitter;
			}
		}

		// 4. 创建用户专属 Agent（内部加载短期记忆 + 绑定长期记忆）
		ReActAgent agent = agentFactory.create(userId, conversation.getId(), buildSystemPrompt(request.mode()));

		Msg msg = Msg.builder().textContent(input.message()).build();
		StreamOptions options = StreamOptions.builder()
				.eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
				.incremental(true)
				.build();

		// 收集完整回复（用于持久化）
		StringBuilder fullResponse = new StringBuilder();
		String userMessage = request.message();

		agent.stream(List.of(msg), options).subscribe(
				event -> sendDelta(emitter, event, fullResponse),
				error -> {
					sendErrorAndComplete(emitter, error);
					// 错误时也持久化用户消息（助手回复为空或部分）
					persistMessages(conversation.getId(), userId, userMessage, fullResponse.toString());
				},
				() -> {
					// 持久化对话消息
					persistMessages(conversation.getId(), userId, userMessage, fullResponse.toString());
					// 更新会话活跃时间
					conversationService.touch(conversation.getId());
					// 首条消息自动生成标题
					if (conversation.getTitle() != null && "新对话".equals(conversation.getTitle())
							&& messageMapper.countByConversationId(conversation.getId()) <= 2) {
						conversationService.autoTitle(conversation.getId(), userMessage);
					}
					// 发送显式 done 帧
					try {
						emitter.send(SseEmitter.event().name("done").data("{}"));
					}
					catch (Exception ignored) {
					}
					emitter.complete();
				});
		return emitter;
	}

	/**
	 * 解析会话：conversationId 为空时自动创建新会话，否则校验归属。
	 */
	private Conversation resolveConversation(Long userId, String conversationIdStr, String firstMessage) {
		if (StringUtils.hasText(conversationIdStr)) {
			try {
				Long conversationId = Long.parseLong(conversationIdStr);
				Conversation conversation = conversationService.getByIdForUser(conversationId, userId);
				if (conversation != null) {
					return conversation;
				}
				log.warn("会话不属于当前用户，创建新会话：userId={}, conversationId={}", userId, conversationId);
			}
			catch (NumberFormatException e) {
				log.warn("无效的 conversationId：{}", conversationIdStr);
			}
		}
		// 创建新会话（标题首条消息后自动更新）
		return conversationService.create(userId, "新对话");
	}

	/**
	 * 持久化一轮对话的用户消息和助手回复。
	 */
	private void persistMessages(Long conversationId, Long userId, String userMessage, String assistantMessage) {
		if (StringUtils.hasText(userMessage)) {
			messageMapper.insert(new com.example.jarvis.model.Message(conversationId, userId, "user", userMessage));
		}
		if (StringUtils.hasText(assistantMessage)) {
			messageMapper.insert(new com.example.jarvis.model.Message(conversationId, userId, "assistant", assistantMessage));
		}
	}

	/**
	 * 构建系统提示词：基础提示 + 模式特定提示。
	 * 长期记忆由框架（LongTermMemoryMode.STATIC_CONTROL）自动 retrieve 注入。
	 */
	private String buildSystemPrompt(String mode) {
		String base = agentScopeConfig.getAgent().getSysPrompt();
		if (!StringUtils.hasText(mode)) {
			return base;
		}
		return switch (mode) {
			case "source-analysis" -> {
				String prompt = agentScopeConfig.getAgent().getSourceAnalysisSysPrompt();
				yield StringUtils.hasText(prompt) ? prompt : base;
			}
			default -> base;
		};
	}

	/**
	 * 从 Spring Security Context 获取当前登录用户ID。
	 */
	private Long currentUserId() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof Long id) {
			return id;
		}
		if (principal instanceof Integer id) {
			return id.longValue();
		}
		throw new IllegalStateException("无法获取当前用户身份，请重新登录");
	}

	private void sendDelta(SseEmitter emitter, Event event, StringBuilder fullResponse) {
		try {
			if (event.getType() == EventType.AGENT_RESULT) {
				// 不在此处 complete，交给 onComplete 回调统一发送 done 帧后关闭
				return;
			}
			if (event.isLast()) {
				return;
			}
			Msg msg = event.getMessage();
			// ThinkingBlock：模型内部思考过程（如 DeepSeek-R1 的 reasoning_content）
			var thinkingBlocks = msg.getContentBlocks(ThinkingBlock.class);
			if (!thinkingBlocks.isEmpty()) {
				String thinking = thinkingBlocks.stream()
						.map(ThinkingBlock::getThinking)
						.filter(t -> t != null && !t.isEmpty())
						.collect(Collectors.joining());
				if (!thinking.isEmpty()) {
					emitter.send(SseEmitter.event().name("reasoning").data(thinking));
				}
			}
			// TextBlock：正常回复文本（ReAct 的 Thought + 最终回复）
			String text = msg.getTextContent();
			if (text != null && !text.isEmpty()) {
				fullResponse.append(text);
				emitter.send(SseEmitter.event().data(text));
			}
		}
		catch (Exception e) {
			sendErrorAndComplete(emitter, e);
		}
	}

	private void sendErrorAndComplete(SseEmitter emitter, Throwable error) {
		log.warn("Agent stream error: {}", error.getMessage());
		try {
			String userMessage = toUserMessage(error);
			emitter.send(SseEmitter.event()
					.name("error")
					.data(Map.of("error", userMessage)));
		}
		catch (IOException ignored) {
			// connection already lost, nothing to do
		}
		finally {
			emitter.complete();
		}
	}

	private String toUserMessage(Throwable error) {
		if (error instanceof AuthenticationException) {
			return "模型服务认证失败：请检查 AGENTSCOPE_API_KEY / AGENTSCOPE_BASE_URL / AGENTSCOPE_MODEL 环境变量配置，当前使用的占位符 key 无效。";
		}
		String msg = error.getMessage();
		if (msg != null && msg.contains("401")) {
			return "模型服务认证失败（401）：请检查 API Key 是否正确。";
		}
		if (msg != null && msg.contains("429")) {
			return "模型服务限流（429）：请求过于频繁，请稍后再试。";
		}
		return "模型服务异常：" + (msg != null ? msg.split("\\|")[0].trim() : error.getClass().getSimpleName());
	}

	/**
	 * 双入口之一：聊天注入。检索知识库，命中时把编号片段与用户问题拼装为增强 prompt，
	 * 并要求模型以 [n] 标注引用（编号与返回给前端的 sources 一一对应）。
	 * 检索失败（如 Ollama 未启动）不影响聊天，降级为原始消息。
	 */
	private AugmentedInput augmentWithKnowledge(String message) {
		if (!StringUtils.hasText(message)) {
			return new AugmentedInput(message, List.of());
		}
		try {
			KnowledgeService.RagInjection injection =
					knowledgeService.buildInjection(message.strip(), null);
			if (injection == null) {
				return new AugmentedInput(message, List.of());
			}
			log.info("RAG 注入：命中知识库片段 {} 条", injection.hits().size());
			List<ChatSource> sources = new ArrayList<>();
			for (int i = 0; i < injection.hits().size(); i++) {
				KnowledgeService.SearchHit hit = injection.hits().get(i);
				sources.add(new ChatSource(i + 1, hit.documentId(), hit.documentTitle(),
						hit.seq(), hit.score(), snippet(hit.content())));
			}
			String augmented = """
					请优先依据以下知识库检索结果回答问题；引用了某条结果时，必须在对应内容后
					用 [编号] 标注其来源（如 [1]）；检索结果与问题无关时请忽略并正常回答，
					不要编造不存在的引用编号。
					回答使用标准 Markdown 格式：标题井号后留空格（如 "### 标题"），列表符号
					后留空格（如 "- 条目"、"1. 条目"）。

					【知识库检索结果】
					%s

					【用户问题】
					%s""".formatted(injection.context(), message.strip());
			return new AugmentedInput(augmented, sources);
		}
		catch (Exception e) {
			log.warn("知识库检索失败，降级为普通对话：{}", e.getMessage());
			return new AugmentedInput(message, List.of());
		}
	}

	/** 增强后的用户消息 + 本次回答可引用的来源片段 */
	private record AugmentedInput(String message, List<ChatSource> sources) {
	}

	/** 来源卡片展示用的片段摘要 */
	private String snippet(String content) {
		String oneLine = content.strip().replaceAll("\\s+", " ");
		return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
	}

}
