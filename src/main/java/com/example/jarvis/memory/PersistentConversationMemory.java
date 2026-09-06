package com.example.jarvis.memory;

import java.util.ArrayList;
import java.util.List;

import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.model.Message;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 持久化短期记忆：实现 AgentScope {@link Memory} 接口，数据落盘 MySQL。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>内部维护内存消息列表（满足 Memory 接口契约，Agent 推理时直接读内存）</li>
 *   <li>{@link #loadFromDb(int)} 从 MySQL message 表加载历史注入内存</li>
 *   <li>{@link #saveMessage} 写入 MySQL，由 AgentController 在回复后调用</li>
 *   <li>明确不使用 AgentScope InMemoryMemory，重启后从 MySQL 重新加载即可恢复</li>
 *   <li>上下文窗口管理：loadFromDb 只取最近 N 条（V1 简单截断，V2 接 LLM 摘要压缩）</li>
 * </ul>
 */
public class PersistentConversationMemory implements Memory {

	private static final Logger log = LoggerFactory.getLogger(PersistentConversationMemory.class);

	/** 默认最大消息条数（超出只保留最近 N 条，防止 Token 超限） */
	private static final int DEFAULT_MAX_MESSAGES = 20;

	private final Long conversationId;

	private final Long userId;

	private final MessageMapper messageMapper;

	/** 内存消息列表（AgentScope Memory 接口的实际存储） */
	private final List<Msg> messages = new ArrayList<>();

	private int maxMessages = DEFAULT_MAX_MESSAGES;

	public PersistentConversationMemory(Long conversationId, Long userId, MessageMapper messageMapper) {
		this.conversationId = conversationId;
		this.userId = userId;
		this.messageMapper = messageMapper;
	}

	public PersistentConversationMemory(Long conversationId, Long userId, MessageMapper messageMapper,
			int maxMessages) {
		this(conversationId, userId, messageMapper);
		this.maxMessages = maxMessages;
	}

	/**
	 * 从 MySQL 加载历史消息注入内存列表。
	 * 只取最近 maxMessages 条（上下文窗口管理，V1 简单截断）。
	 */
	public void loadFromDb() {
		loadFromDb(maxMessages);
	}

	/**
	 * 从 MySQL 加载历史消息，限制最多 maxMessages 条。
	 */
	public void loadFromDb(int limit) {
		messages.clear();
		if (conversationId == null) {
			return;
		}
		try {
			List<Message> history = messageMapper.findByConversationId(conversationId, limit);
			for (Message m : history) {
				messages.add(toMsg(m));
			}
			log.debug("短期记忆加载：conversationId={}, 条数={}", conversationId, messages.size());
		}
		catch (Exception e) {
			log.warn("短期记忆加载失败，使用空历史：conversationId={}, error={}", conversationId, e.getMessage());
		}
	}

	/**
	 * 持久化单条消息到 MySQL（由 AgentController 在用户发送和助手回复后调用）。
	 */
	public void saveMessage(String role, String content) {
		if (conversationId == null || content == null || content.isBlank()) {
			return;
		}
		try {
			Message msg = new Message(conversationId, userId, role, content);
			messageMapper.insert(msg);
		}
		catch (Exception e) {
			log.error("消息持久化失败：conversationId={}, role={}, error={}", conversationId, role, e.getMessage());
		}
	}

	// === Memory 接口实现 ===

	@Override
	public void addMessage(Msg message) {
		messages.add(message);
	}

	@Override
	public List<Msg> getMessages() {
		return new ArrayList<>(messages);
	}

	@Override
	public void deleteMessage(int index) {
		if (index >= 0 && index < messages.size()) {
			messages.remove(index);
		}
	}

	@Override
	public void clear() {
		messages.clear();
	}

	// === 转换工具 ===

	/** DB Message → AgentScope Msg */
	private Msg toMsg(Message m) {
		MsgRole role = switch (m.getRole()) {
			case "user" -> MsgRole.USER;
			case "assistant" -> MsgRole.ASSISTANT;
			case "system" -> MsgRole.SYSTEM;
			case "tool" -> MsgRole.TOOL;
			default -> MsgRole.USER;
		};
		return Msg.builder().role(role).textContent(m.getContent()).build();
	}

	public Long getConversationId() {
		return conversationId;
	}

}
