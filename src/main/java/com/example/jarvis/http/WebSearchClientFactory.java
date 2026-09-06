package com.example.jarvis.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 根据配置选择搜索后端实例。
 * provider=bing 但 Key 为空时回退到 bing-html。
 */
@Component
public class WebSearchClientFactory {

	private static final Logger log = LoggerFactory.getLogger(WebSearchClientFactory.class);

	private final WebSearchConfig config;
	private final DuckDuckGoSearchClient duckDuckGoClient;
	private final BingSearchClient bingClient;
	private final BingHtmlSearchClient bingHtmlClient;

	public WebSearchClientFactory(WebSearchConfig config,
			DuckDuckGoSearchClient duckDuckGoClient,
			BingSearchClient bingClient,
			BingHtmlSearchClient bingHtmlClient) {
		this.config = config;
		this.duckDuckGoClient = duckDuckGoClient;
		this.bingClient = bingClient;
		this.bingHtmlClient = bingHtmlClient;
	}

	public WebSearchClient getClient() {
		String provider = config.getProvider();
		if (provider == null) {
			provider = "bing-html";
		}
		provider = provider.toLowerCase().trim();

		if ("bing".equals(provider)) {
			String key = config.getBing().getApiKey();
			if (key == null || key.isBlank()) {
				log.warn("Bing API Key 未配置，回退到 Bing HTML 搜索");
				return bingHtmlClient;
			}
			log.info("使用 Bing Search API 作为搜索后端");
			return bingClient;
		}

		if ("duckduckgo".equals(provider)) {
			log.info("使用 DuckDuckGo 作为搜索后端");
			return duckDuckGoClient;
		}

		log.info("使用 Bing HTML 作为搜索后端");
		return bingHtmlClient;
	}

	public WebSearchConfig getConfig() {
		return config;
	}
}
