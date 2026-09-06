package com.example.jarvis.config;

import com.example.jarvis.memory.PersistentConversationMemory;
import com.example.jarvis.memory.VectorLongTermMemory;
import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.mapper.UserMemoryMapper;
import com.example.jarvis.rag.OllamaEmbeddingClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂：请求级创建独立的 {@link ReActAgent} 实例。
 *
 * <p><b>多租户隔离基础</b>：
 * <ul>
 *   <li>每次对话请求创建独立 Agent，绑定该用户专属的短期+长期记忆</li>
 *   <li>共享单例：OpenAIChatModel（LLM client，无状态）、Toolkit（工具集，无状态）</li>
 *   <li>请求结束 Agent 可被 GC 回收，不持有跨请求状态，并发安全</li>
 * </ul>
 */
@Component
public class AgentFactory {

	private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

	private final OpenAIChatModel sharedModel;

	private final Toolkit sharedToolkit;

	private final MessageMapper messageMapper;

	private final UserMemoryMapper userMemoryMapper;

	private final OllamaEmbeddingClient embeddingClient;

	private final AgentScopeConfig agentScopeConfig;

	public AgentFactory(OpenAIChatModel sharedModel, Toolkit sharedToolkit,
			MessageMapper messageMapper, UserMemoryMapper userMemoryMapper,
			OllamaEmbeddingClient embeddingClient, AgentScopeConfig agentScopeConfig) {
		this.sharedModel = sharedModel;
		this.sharedToolkit = sharedToolkit;
		this.messageMapper = messageMapper;
		this.userMemoryMapper = userMemoryMapper;
		this.embeddingClient = embeddingClient;
		this.agentScopeConfig = agentScopeConfig;
	}

	/**
	 * 为指定用户+会话创建独立 ReActAgent。
	 *
	 * @param userId        用户ID（多租户隔离键）
	 * @param conversationId 会话ID（短期记忆加载/持久化的范围）
	 * @param systemPrompt  系统提示词（基础提示 + 长期记忆注入）
	 * @return 绑定该用户专属记忆的 ReActAgent 实例
	 */
	public ReActAgent create(Long userId, Long conversationId, String systemPrompt) {
		long start = System.currentTimeMillis();

		// 1. 创建短期记忆并从 DB 加载历史
		PersistentConversationMemory shortMem =
				new PersistentConversationMemory(conversationId, userId, messageMapper);
		shortMem.loadFromDb();

		// 2. 创建长期记忆（用户专属）
		VectorLongTermMemory longMem =
				new VectorLongTermMemory(userId, userMemoryMapper, embeddingClient, sharedModel);

		// 3. 构建 ReActAgent
		AgentScopeConfig.AgentConfig cfg = agentScopeConfig.getAgent();
		String agentName = cfg.getName() + "-" + userId + "-" + conversationId;

		ReActAgent agent = ReActAgent.builder()
				.name(agentName)
				.sysPrompt(systemPrompt)
				.model(sharedModel)          // 共享单例（无状态）
				.toolkit(sharedToolkit)      // 共享单例（无状态）
				.memory(shortMem)            // 用户专属短期记忆
				.longTermMemory(longMem)     // 用户专属长期记忆
				.longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)  // 框架自动 record/retrieve
				.maxIters(cfg.getMaxIters())
				.build();

		log.debug("Agent 创建完成：userId={}, conversationId={}, 耗时={}ms",
				userId, conversationId, System.currentTimeMillis() - start);
		return agent;
	}

}
