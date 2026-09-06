package com.example.jarvis.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 网页搜索配置，绑定 application.properties 中的 websearch.* 属性。 */
@Component
@ConfigurationProperties(prefix = "websearch")
public class WebSearchConfig {

	private String provider = "duckduckgo";
	private DuckDuckGoConfig duckduckgo = new DuckDuckGoConfig();
	private BingConfig bing = new BingConfig();
	private int maxResults = 5;
	private int maxCallsPerConversation = 3;

	public static class DuckDuckGoConfig {
		private String url = "https://html.duckduckgo.com/html/?q=";
		public String getUrl() { return url; }
		public void setUrl(String url) { this.url = url; }
	}

	public static class BingConfig {
		private String apiKey = "";
		public String getApiKey() { return apiKey; }
		public void setApiKey(String apiKey) { this.apiKey = apiKey; }
	}

	public String getProvider() { return provider; }
	public void setProvider(String provider) { this.provider = provider; }
	public DuckDuckGoConfig getDuckduckgo() { return duckduckgo; }
	public void setDuckduckgo(DuckDuckGoConfig duckduckgo) { this.duckduckgo = duckduckgo; }
	public BingConfig getBing() { return bing; }
	public void setBing(BingConfig bing) { this.bing = bing; }
	public int getMaxResults() { return maxResults; }
	public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
	public int getMaxCallsPerConversation() { return maxCallsPerConversation; }
	public void setMaxCallsPerConversation(int maxCallsPerConversation) { this.maxCallsPerConversation = maxCallsPerConversation; }
}
