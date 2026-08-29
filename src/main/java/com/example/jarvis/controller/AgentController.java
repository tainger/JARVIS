package com.example.jarvis.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.rag.KnowledgeService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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

	private final Agent jarvisAgent;

	private final KnowledgeService knowledgeService;

	public AgentController(Agent jarvisAgent, KnowledgeService knowledgeService) {
		this.jarvisAgent = jarvisAgent;
		this.knowledgeService = knowledgeService;
	}

	@PostMapping("/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		Msg input = Msg.builder().textContent(augmentWithKnowledge(request.message())).build();
		Msg response = jarvisAgent.call(input).block();
		return new ChatResponse(response.getTextContent());
	}

	/**
	 * Streaming chat endpoint (Server-Sent Events).
	 * Each SSE data frame carries a partial text delta of the final answer;
	 * the stream ends when the agent emits its last event.
	 */
	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chatStream(@RequestBody ChatRequest request) {
		SseEmitter emitter = new SseEmitter(0L); // no timeout, stream may be long
		Msg input = Msg.builder().textContent(augmentWithKnowledge(request.message())).build();
		StreamOptions options = StreamOptions.builder()
				.eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
				.incremental(true)
				.build();

		jarvisAgent.stream(List.of(input), options).subscribe(
				event -> sendDelta(emitter, event),
				error -> sendErrorAndComplete(emitter, error),
				emitter::complete);
		return emitter;
	}

	private void sendDelta(SseEmitter emitter, Event event) {
		try {
			String text = event.getMessage().getTextContent();
			if (event.getType() == EventType.AGENT_RESULT) {
				emitter.complete();
				return;
			}
			if (!event.isLast() && text != null && !text.isEmpty()) {
				emitter.send(SseEmitter.event().data(text));
			}
		} catch (Exception e) {
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
		} catch (IOException ignored) {
			// connection already lost, nothing to do
		} finally {
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
	 * 双入口之一：聊天注入。检索知识库，命中时把结果片段与用户问题拼装为增强 prompt。
	 * 检索失败（如 Ollama 未启动）不影响聊天，降级为原始消息。
	 */
	private String augmentWithKnowledge(String message) {
		if (!StringUtils.hasText(message)) {
			return message;
		}
		try {
			String context = knowledgeService.buildContext(message.strip(), null);
			if (context == null) {
				return message;
			}
			log.info("RAG 注入：命中知识库片段");
			return """
					请优先依据以下知识库检索结果回答问题；若与问题无关请忽略。

					【知识库检索结果】
					%s

					【用户问题】
					%s""".formatted(context, message.strip());
		}
		catch (Exception e) {
			log.warn("知识库检索失败，降级为普通对话：{}", e.getMessage());
			return message;
		}
	}

}
