package com.example.jarvis.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import com.example.jarvis.tool.TaskTools;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agentscope")
public class AgentScopeConfig {

	private final ModelConfig model = new ModelConfig();

	private final AgentConfig agent = new AgentConfig();

	public ModelConfig getModel() {
		return model;
	}

	public AgentConfig getAgent() {
		return agent;
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

	@Bean
	public OpenAIChatModel agentscopeModel() {
		return OpenAIChatModel.builder()
				.apiKey(model.getApiKey())
				.baseUrl(model.getBaseUrl())
				.modelName(model.getName())
				.build();
	}

	@Bean
	public Toolkit agentscopeToolkit(TaskTools taskTools) {
		Toolkit toolkit = new Toolkit();
		toolkit.registerTool(taskTools);
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

}
