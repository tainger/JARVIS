package com.example.jarvis.rag;

import java.util.List;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 双入口之一：把知识库检索暴露为 AgentScope 工具，
 * 由 ReActAgent 在需要时主动调用。
 */
@Component
public class KnowledgeSearchTools {

	private final KnowledgeService knowledgeService;

	public KnowledgeSearchTools(KnowledgeService knowledgeService) {
		this.knowledgeService = knowledgeService;
	}

	@Tool(description = "Search the JARVIS knowledge base for relevant document fragments. "
			+ "Use this tool whenever the user's question may be answered by uploaded documents "
			+ "(Markdown/text files). Returns the top matching fragments with similarity scores.")
	public String knowledgeSearch(
			@ToolParam(name = "query", description = "The search query, keywords or a question") String query) {
		try {
			List<KnowledgeService.SearchHit> hits = knowledgeService.search(query, null);
			if (hits.isEmpty()) {
				return "知识库中没有找到与该问题相关的内容。";
			}
			StringBuilder sb = new StringBuilder("知识库检索到 ")
					.append(hits.size())
					.append(" 条相关内容：\n\n");
			for (int i = 0; i < hits.size(); i++) {
				KnowledgeService.SearchHit hit = hits.get(i);
				sb.append(String.format("[%d] 来源：%s（片段 %d，相似度 %.3f）%n%s%n%n",
						i + 1, hit.documentTitle(), hit.seq(), hit.score(), hit.content()));
			}
			return sb.toString().strip();
		}
		catch (Exception e) {
			return "知识库检索失败：" + e.getMessage();
		}
	}

}
