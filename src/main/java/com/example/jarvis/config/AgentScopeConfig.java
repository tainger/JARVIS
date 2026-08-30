package com.example.jarvis.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.jarvis.tool.TaskTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConfigurationProperties(prefix = "agentscope")
public class AgentScopeConfig {

	private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

	private final ModelConfig model = new ModelConfig();

	private final AgentConfig agent = new AgentConfig();

	/** MCP 服务器配置（声明式，启动时自动注册） */
	private final McpConfig mcp = new McpConfig();

	public ModelConfig getModel() {
		return model;
	}

	public AgentConfig getAgent() {
		return agent;
	}

	public McpConfig getMcp() {
		return mcp;
	}

	public static class ModelConfig {

		private String apiKey;

		private String baseUrl;

		private String name;

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

	}

	public static class AgentConfig {

		private String name;

		private String sysPrompt;

		private int maxIters = 10;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getSysPrompt() {
			return sysPrompt;
		}

		public void setSysPrompt(String sysPrompt) {
			this.sysPrompt = sysPrompt;
		}

		public int getMaxIters() {
			return maxIters;
		}

		public void setMaxIters(int maxIters) {
			this.maxIters = maxIters;
		}

	}

	/** MCP 服务器集合配置 */
	public static class McpConfig {

		/** 服务器列表 */
		private List<McpServerConfig> servers = new ArrayList<>();

		public List<McpServerConfig> getServers() {
			return servers;
		}

		public void setServers(List<McpServerConfig> servers) {
			this.servers = servers != null ? servers : new ArrayList<>();
		}

	}

	/** 单个 MCP 服务器配置项 */
	public static class McpServerConfig {

		/** 客户端名称（唯一标识，用于 toolkit 注册与移除） */
		private String name;

		/**
		 * 传输方式：stdio / sse / streamableHttp
		 * - stdio: 启动子进程，需配 command + args (+ env)
		 * - sse: SSE 远程服务，需配 url
		 * - streamableHttp: HTTP 流式远程服务，需配 url
		 */
		private String transport = "stdio";

		/** stdio 模式下的可执行命令（如 npx / node / uvx） */
		private String command;

		/** stdio 模式下的命令参数列表 */
		private List<String> args = new ArrayList<>();

		/** stdio 模式下的环境变量 */
		private Map<String, String> env = new HashMap<>();

		/** SSE / streamableHttp 模式下的服务器 URL */
		private String url;

		/** SSE / streamableHttp 模式下的请求头 */
		private Map<String, String> headers = new HashMap<>();

		/** SSE / streamableHttp 模式下的 query 参数 */
		private Map<String, String> queryParams = new HashMap<>();

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getTransport() {
			return transport;
		}

		public void setTransport(String transport) {
			this.transport = transport;
		}

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public List<String> getArgs() {
			return args;
		}

		public void setArgs(List<String> args) {
			this.args = args != null ? args : new ArrayList<>();
		}

		public Map<String, String> getEnv() {
			return env;
		}

		public void setEnv(Map<String, String> env) {
			this.env = env != null ? env : new HashMap<>();
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public Map<String, String> getHeaders() {
			return headers;
		}

		public void setHeaders(Map<String, String> headers) {
			this.headers = headers != null ? headers : new HashMap<>();
		}

		public Map<String, String> getQueryParams() {
			return queryParams;
		}

		public void setQueryParams(Map<String, String> queryParams) {
			this.queryParams = queryParams != null ? queryParams : new HashMap<>();
		}

	}

	@Bean
	public OpenAIChatModel agentscopeModel() {
		// ===== 占位符 Key 检查（启动时立刻提醒，避免用户点了对话才看到 401）=====
		String key = model.getApiKey();
		boolean isPlaceholder =
				key == null
				|| key.isEmpty()
				|| key.contains("sk-demo-key")
				|| key.contains("sk-your-real")
				|| key.startsWith("your-")
				|| key.contains("placeholder");

		if (isPlaceholder) {
			log.warn("""
					\n
					╔══════════════════════════════════════════════════════════════════╗
					║ ⚠️  AGENTSCOPE_API_KEY 仍是占位符，AI 对话功能将不可用！          ║
					╠══════════════════════════════════════════════════════════════════╣
					║                                                                  ║
					║   当前生效值: {}               ║
					║                                                                  ║
					║   本地开发修复方式（任选其一）：                                   ║
					║    1) 复制 .env.example → .env，填真实 Key：                     ║
					║         cp .env.example .env && vim .env                         ║
					║         → 修改 AGENTSCOPE_API_KEY=sk-xxx                         ║
					║         → 然后通过 ./run.sh 启动（自动加载 .env）                 ║
					║                                                                  ║
					║    2) 直接在当前终端 export 后再启动：                            ║
					║         export AGENTSCOPE_API_KEY=sk-xxx                         ║
					║         ./mvnw spring-boot:run                                   ║
					║                                                                  ║
					║    3) IDE（IntelliJ IDEA）：Run → Edit Configurations →        ║
					║       Modify options → Environment variables → 手动加键值对。   ║
					║                                                                  ║
					║   获取 Key：https://platform.deepseek.com/                        ║
					║                                                                  ║
					╚══════════════════════════════════════════════════════════════════╝
					""".formatted(key == null ? "<null>" : key.length() > 40
							? key.substring(0, 40) + "...(截断)"
							: key));
		} else {
			log.info("AGENTSCOPE_MODEL: {} @ {}（api-key 已配置，长度={}）",
					model.getName(), model.getBaseUrl(),
					key == null ? 0 : key.length());
		}

		return OpenAIChatModel.builder()
				.apiKey(model.getApiKey())
				.baseUrl(model.getBaseUrl())
				.modelName(model.getName())
				.build();
	}

	@Bean
	public Toolkit agentscopeToolkit(TaskTools taskTools,
			com.example.jarvis.rag.KnowledgeSearchTools knowledgeSearchTools) {
		Toolkit toolkit = new Toolkit();
		toolkit.registerTool(taskTools);
		toolkit.registerTool(knowledgeSearchTools);
		registerMcpServers(toolkit);
		return toolkit;
	}

	@Bean
	public ReActAgent jarvisAgent(OpenAIChatModel agentscopeModel, Toolkit agentscopeToolkit) {
		return ReActAgent.builder()
				.name(agent.getName())
				.sysPrompt(agent.getSysPrompt())
				.model(agentscopeModel)
				.toolkit(agentscopeToolkit)
				.maxIters(agent.getMaxIters())
				.build();
	}

	/**
	 * 根据配置循环注册所有声明的 MCP 服务器到 Toolkit。
	 * 单个 MCP 注册失败不影响整体启动（仅告警）。
	 */
	private void registerMcpServers(Toolkit toolkit) {
		List<McpServerConfig> servers = mcp.getServers();
		if (servers == null || servers.isEmpty()) {
			log.info("No MCP servers configured, skipping registration.");
			return;
		}
		for (McpServerConfig server : servers) {
			if (!StringUtils.hasText(server.getName())) {
				log.warn("MCP server config missing name, skipped: {}", server);
				continue;
			}
			try {
				McpClientWrapper wrapper = buildClient(server);
				if (wrapper == null) {
					continue;
				}
				// 注册到 toolkit（异步 Mono，这里 block 等待握手完成，确保启动后即可用）
				toolkit.registerMcpClient(wrapper).block();
				log.info("Registered MCP server '{}' (transport={}, tools={})",
						server.getName(), server.getTransport(), getToolNames(toolkit));
			} catch (Exception e) {
				log.error("Failed to register MCP server '{}': {}", server.getName(), e.getMessage(), e);
			}
		}
	}

	/** 根据配置项构造 MCP 客户端 wrapper（同步阻塞完成握手） */
	private McpClientWrapper buildClient(McpServerConfig server) {
		McpClientBuilder builder = McpClientBuilder.create(server.getName());
		String transport = server.getTransport() == null ? "stdio" : server.getTransport().toLowerCase();
		switch (transport) {
			case "stdio":
				if (!StringUtils.hasText(server.getCommand())) {
					log.warn("MCP server '{}' uses stdio but no command configured, skipped.", server.getName());
					return null;
				}
				builder.stdioTransport(server.getCommand(), server.getArgs(), server.getEnv());
				break;
			case "sse":
				if (!StringUtils.hasText(server.getUrl())) {
					log.warn("MCP server '{}' uses sse but no url configured, skipped.", server.getName());
					return null;
				}
				builder.sseTransport(server.getUrl());
				break;
			case "streamablehttp":
			case "streamable_http":
				if (!StringUtils.hasText(server.getUrl())) {
					log.warn("MCP server '{}' uses streamableHttp but no url configured, skipped.", server.getName());
					return null;
				}
				builder.streamableHttpTransport(server.getUrl());
				break;
			default:
				log.warn("MCP server '{}' has unknown transport '{}', skipped.", server.getName(), transport);
				return null;
		}
		if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
			builder.headers(server.getHeaders());
		}
		if (server.getQueryParams() != null && !server.getQueryParams().isEmpty()) {
			builder.queryParams(server.getQueryParams());
		}
		// 同步阻塞完成与 MCP server 的握手；MCP 协议初始化通常 < 5s
		return builder.buildSync();
	}

	private Set<String> getToolNames(Toolkit toolkit) {
		try {
			return toolkit.getToolNames();
		} catch (Exception e) {
			return Set.of();
		}
	}

}
