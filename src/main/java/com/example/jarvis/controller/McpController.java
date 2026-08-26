package com.example.jarvis.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.jarvis.config.AgentScopeConfig;
import com.example.jarvis.config.AgentScopeConfig.McpServerConfig;
import io.agentscope.core.tool.Toolkit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP (Model Context Protocol) 集成信息查询接口。
 * 只读：列出 application.properties 中声明的 MCP server 配置，
 * 以及 Toolkit 当前实际生效的工具集合（含本地工具 + MCP 工具）。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

	private final AgentScopeConfig config;

	private final Toolkit toolkit;

	public McpController(AgentScopeConfig config, Toolkit toolkit) {
		this.config = config;
		this.toolkit = toolkit;
	}

	/** 列出已声明的 MCP server 配置及 Toolkit 中所有生效工具。 */
	@GetMapping
	public Map<String, Object> overview() {
		List<McpServerConfig> declared = config.getMcp().getServers();
		List<Map<String, Object>> servers = declared.stream()
				.map(this::toView)
				.collect(Collectors.toList());

		Set<String> tools = toolkit.getToolNames();
		List<String> sortedTools = new ArrayList<>(tools);
		sortedTools.sort(Comparator.naturalOrder());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("configuredServers", servers);
		result.put("configuredCount", servers.size());
		result.put("activeTools", sortedTools);
		result.put("activeToolCount", sortedTools.size());
		return result;
	}

	private Map<String, Object> toView(McpServerConfig s) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("name", s.getName());
		view.put("transport", s.getTransport());
		if (s.getUrl() != null && !s.getUrl().isEmpty()) {
			view.put("url", s.getUrl());
		}
		if (s.getCommand() != null && !s.getCommand().isEmpty()) {
			List<String> cmd = new ArrayList<>();
			cmd.add(s.getCommand());
			if (s.getArgs() != null) {
				cmd.addAll(s.getArgs());
			}
			view.put("command", cmd);
		}
		return view;
	}

}
