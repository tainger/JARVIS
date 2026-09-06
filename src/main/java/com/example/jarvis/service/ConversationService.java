package com.example.jarvis.service;

import java.util.List;

import com.example.jarvis.mapper.ConversationMapper;
import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.model.Conversation;
import com.example.jarvis.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话管理服务：创建/查询/重命名/删除会话，多租户隔离校验。
 */
@Service
public class ConversationService {

	private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

	private final ConversationMapper conversationMapper;

	private final MessageMapper messageMapper;

	public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
		this.conversationMapper = conversationMapper;
		this.messageMapper = messageMapper;
	}

	/**
	 * 创建新会话
	 */
	public Conversation create(Long userId, String title) {
		Conversation conversation = new Conversation(userId, title != null ? title : "新对话");
		conversationMapper.insert(conversation);
		return conversation;
	}

	/**
	 * 查询用户的会话列表
	 */
	public List<Conversation> listByUser(Long userId) {
		return conversationMapper.findByUserId(userId);
	}

	/**
	 * 获取会话详情（带用户隔离校验）
	 * @return 会话对象，不属于该用户则返回 null
	 */
	public Conversation getByIdForUser(Long conversationId, Long userId) {
		if (conversationMapper.countByIdAndUserId(conversationId, userId) == 0) {
			return null;
		}
		return conversationMapper.findById(conversationId);
	}

	/**
	 * 获取会话消息历史（分页）
	 */
	public List<Message> getMessages(Long conversationId, int offset, int limit) {
		return messageMapper.findByConversationIdPaginated(conversationId, offset, limit);
	}

	/**
	 * 重命名会话（带用户隔离校验）
	 * @return true=成功, false=会话不属于该用户
	 */
	public boolean rename(Long conversationId, Long userId, String title) {
		if (conversationMapper.countByIdAndUserId(conversationId, userId) == 0) {
			return false;
		}
		conversationMapper.updateTitle(conversationId, title);
		return true;
	}

	/**
	 * 更新会话最近活跃时间
	 */
	public void touch(Long conversationId) {
		conversationMapper.updateLastActiveAt(conversationId);
	}

	/**
	 * 更新会话标题（首条消息后自动截取前20字）
	 */
	public void autoTitle(Long conversationId, String firstMessage) {
		String title = firstMessage.strip();
		if (title.length() > 20) {
			title = title.substring(0, 20) + "…";
		}
		conversationMapper.updateTitle(conversationId, title);
	}

	/**
	 * 删除会话（级联删除消息，带用户隔离校验）
	 * @return true=成功, false=会话不属于该用户
	 */
	@Transactional
	public boolean delete(Long conversationId, Long userId) {
		if (conversationMapper.countByIdAndUserId(conversationId, userId) == 0) {
			return false;
		}
		messageMapper.deleteByConversationId(conversationId);
		conversationMapper.deleteById(conversationId);
		return true;
	}

}
