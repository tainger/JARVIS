package com.example.jarvis.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.jarvis.mapper.MessageMapper;
import com.example.jarvis.mapper.UserProfileMapper;
import com.example.jarvis.model.Message;
import com.example.jarvis.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ChatResponse;

@Service
public class ProfileExtractionService {

	private static final Logger log = LoggerFactory.getLogger(ProfileExtractionService.class);

	private static final int RECENT_MESSAGE_LIMIT = 20;

	private final UserProfileMapper profileMapper;
	private final MessageMapper messageMapper;
	private final OpenAIChatModel chatModel;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public ProfileExtractionService(UserProfileMapper profileMapper,
			MessageMapper messageMapper,
			OpenAIChatModel chatModel) {
		this.profileMapper = profileMapper;
		this.messageMapper = messageMapper;
		this.chatModel = chatModel;
	}

	public void extractAsync(Long userId) {
		CompletableFuture.runAsync(() -> {
			try {
				extract(userId);
				log.info("用户画像提取完成: userId={}", userId);
			} catch (Exception e) {
				log.warn("画像提取失败: userId={}, error={}", userId, e.getMessage());
			}
		});
	}

	public UserProfile extract(Long userId) {
		List<Message> recent = getRecentConversations(userId);
		if (recent.isEmpty()) {
			log.info("无对话历史，跳过画像提取: userId={}", userId);
			return null;
		}

		String dialogue = buildDialogue(recent);
		String llmOutput = callLlm(dialogue);
		if (llmOutput == null || llmOutput.isBlank()) {
			return null;
		}

		UserProfile parsed = parseProfile(llmOutput);
		if (parsed == null) {
			return null;
		}

		UserProfile existing = profileMapper.findByUserId(userId);
		if (existing == null) {
			parsed.setUserId(userId);
			parsed.setConversationCount(0);
			profileMapper.insert(parsed);
		} else {
			existing.setTechStack(parsed.getTechStack());
			existing.setFrequentTopics(parsed.getFrequentTopics());
			existing.setAnswerStyle(parsed.getAnswerStyle());
			existing.setProjectContext(parsed.getProjectContext());
			existing.setRawSummary(parsed.getRawSummary());
			profileMapper.update(existing);
		}
		return profileMapper.findByUserId(userId);
	}

	private List<Message> getRecentConversations(Long userId) {
		try {
			return messageMapper.findRecentByUserId(userId, RECENT_MESSAGE_LIMIT);
		} catch (Exception e) {
			log.warn("查询用户最近对话失败: userId={}, error={}", userId, e.getMessage());
			return Collections.emptyList();
		}
	}

	private String buildDialogue(List<Message> messages) {
		StringBuilder sb = new StringBuilder();
		for (Message msg : messages) {
			String role = "user".equals(msg.getRole()) ? "用户" : "助手";
			sb.append(role).append("：").append(truncate(msg.getContent(), 300)).append("\n\n");
		}
		return sb.toString();
	}

	private String callLlm(String dialogue) {
		String prompt = """
				你是一个用户画像分析专家。根据以下对话历史，提取用户的画像信息。
				请以 JSON 格式返回，包含以下字段：
				- tech_stack: 用户的技术栈偏好（如 Java, Spring Boot, React, Python）
				- frequent_topics: 用户常问的主题（如 Nacos架构, 分布式事务, 任务管理）
				- answer_style: 用户偏好的回答风格（如 简洁代码示例, 详细原理解释）
				- project_context: 用户的项目上下文（如 正在分析Nacos源码, 开发运维工具）
				- summary: 一句话总结用户画像

				对话历史：
				%s

				请只返回 JSON，不要其他文字。示例：
				{"tech_stack":"Java, Spring Boot","frequent_topics":"Nacos架构, 分布式事务","answer_style":"简洁代码示例","project_context":"分析Nacos源码","summary":"Java后端工程师，偏好简洁回答"}
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
			return text.toString();
		} catch (Exception e) {
			log.warn("LLM 画像提取失败: {}", e.getMessage());
			return null;
		}
	}

	private UserProfile parseProfile(String llmOutput) {
		try {
			String json = llmOutput.strip();
			if (json.startsWith("```")) {
				int firstNewline = json.indexOf('\n');
				if (firstNewline > 0) {
					json = json.substring(firstNewline + 1);
				}
				if (json.endsWith("```")) {
					json = json.substring(0, json.length() - 3);
				}
			}
			JsonNode node = objectMapper.readTree(json);
			UserProfile profile = new UserProfile();
			profile.setTechStack(getTextSafe(node, "tech_stack"));
			profile.setFrequentTopics(getTextSafe(node, "frequent_topics"));
			profile.setAnswerStyle(getTextSafe(node, "answer_style"));
			profile.setProjectContext(getTextSafe(node, "project_context"));
			profile.setRawSummary(getTextSafe(node, "summary"));
			return profile;
		} catch (Exception e) {
			log.warn("画像 JSON 解析失败: {}", e.getMessage());
			return null;
		}
	}

	private String getTextSafe(JsonNode node, String field) {
		JsonNode child = node.get(field);
		return child != null && !child.isNull() ? child.asText() : null;
	}

	private String truncate(String text, int max) {
		if (text == null) return "";
		return text.length() > max ? text.substring(0, max) + "…" : text;
	}
}
