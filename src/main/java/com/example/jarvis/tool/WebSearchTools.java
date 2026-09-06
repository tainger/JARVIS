package com.example.jarvis.tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.jarvis.http.SearchResult;
import com.example.jarvis.http.WebSearchClient;
import com.example.jarvis.http.WebSearchClientFactory;
import com.example.jarvis.http.WebSearchConfig;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 网页搜索工具集：让 ReAct Agent 在知识库/源码无法回答时搜索互联网。
 * 单次对话搜索频率限制（默认 3 次），防止 Agent 陷入搜索循环。
 */
@Component
public class WebSearchTools {

	private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

	private final WebSearchClient client;
	private final int maxResults;
	private final int maxCalls;

	private final AtomicInteger callCount = new AtomicInteger(0);

	public WebSearchTools(WebSearchClientFactory clientFactory, WebSearchConfig config) {
		this.client = clientFactory.getClient();
		this.maxResults = config.getMaxResults();
		this.maxCalls = config.getMaxCallsPerConversation();
		log.info("WebSearchTools 已初始化: maxResults={}, maxCalls={}", maxResults, maxCalls);
	}

	@Tool(description = "Search the web for current information when the knowledge base "
			+ "and source code don't have relevant answers. Returns titles, snippets, and URLs. "
			+ "Always cite the source URL in your answer when using web search results.")
	public String webSearch(
			@ToolParam(name = "query", description = "The search query, keywords or a question") String query) {

		int current = callCount.incrementAndGet();
		if (current > maxCalls) {
			return "已达到本次对话的最大搜索次数限制（" + maxCalls + " 次）。"
					+ "请基于已有信息回答，或建议用户提供更具体的信息。";
		}

		if (query == null || query.isBlank()) {
			return "搜索查询词不能为空。";
		}

		try {
			List<SearchResult> results = client.search(query, maxResults);
			log.info("webSearch: query='{}', results={}", query, results.size());

			if (results.isEmpty()) {
				return "网络搜索未找到与 \"" + query + "\" 相关的结果。";
			}

			StringBuilder sb = new StringBuilder("搜索 \"")
					.append(query)
					.append("\" 找到 ")
					.append(results.size())
					.append(" 条结果：\n\n");

			int totalLen = sb.length();
			for (int i = 0; i < results.size(); i++) {
				SearchResult r = results.get(i);
				String entry = String.format("[%d] %s%n    URL: %s%n    摘要: %s%n%n",
						i + 1, r.title(), r.url(), r.snippet());

				if (totalLen + entry.length() > 2000) {
					sb.append("(更多结果已截断)\n");
					break;
				}
				sb.append(entry);
				totalLen += entry.length();
			}

			return sb.toString().strip();
		}
		catch (Exception e) {
			log.warn("webSearch 工具执行失败: query='{}', error={}", query, e.getMessage());
			return "网络搜索失败：" + e.getMessage() + "。请基于已有信息回答。";
		}
	}

	/** 重置搜索计数（每次新对话调用）。V1 单例模式下由 AgentFactory 在创建 Agent 前调用。 */
	public void resetCallCount() {
		callCount.set(0);
	}
}
