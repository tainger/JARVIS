package com.example.jarvis.controller;

import java.util.List;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

	private final Agent jarvisAgent;

	public AgentController(Agent jarvisAgent) {
		this.jarvisAgent = jarvisAgent;
	}

	@PostMapping("/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		Msg input = Msg.builder().textContent(request.message()).build();
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
		Msg input = Msg.builder().textContent(request.message()).build();
		StreamOptions options = StreamOptions.builder()
				.eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
				.incremental(true)
				.build();

		jarvisAgent.stream(List.of(input), options).subscribe(
				event -> sendDelta(emitter, event),
				emitter::completeWithError,
				emitter::complete);
		return emitter;
	}

	private void sendDelta(SseEmitter emitter, Event event) {
		try {
			String text = event.getMessage().getTextContent();
			if (event.getType() == EventType.AGENT_RESULT) {
				// Final event: deltas were already delivered via REASONING events.
				emitter.complete();
				return;
			}
			// The terminal REASONING event repeats the full accumulated text;
			// only forward the incremental deltas.
			if (!event.isLast() && text != null && !text.isEmpty()) {
				emitter.send(SseEmitter.event().data(text));
			}
		} catch (Exception e) {
			emitter.completeWithError(e);
		}
	}

}
