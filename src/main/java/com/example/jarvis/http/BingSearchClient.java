package com.example.jarvis.http;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bing Search API 后端。
 * 需要 API Key，返回结构化 JSON。
 */
@Component
public class BingSearchClient implements WebSearchClient {

	private static final Logger log = LoggerFactory.getLogger(BingSearchClient.class);

	private static final int HTTP_TIMEOUT_SECONDS = 10;
	private static final String BING_ENDPOINT = "https://api.bing.microsoft.com/v7.0/search";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final WebSearchConfig config;

	public BingSearchClient(WebSearchConfig config) {
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
				.build();
		this.objectMapper = new ObjectMapper();
		log.info("Bing Search API 后端已初始化");
	}

	@Override
	public List<SearchResult> search(String query, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		String apiKey = config.getBing().getApiKey();
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("Bing API Key 为空，无法搜索");
			return results;
		}

		try {
			String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
			String fullUrl = BING_ENDPOINT + "?q=" + encodedQuery + "&count=" + maxResults;

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(fullUrl))
					.header("Ocp-Apim-Subscription-Key", apiKey)
					.timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				log.warn("Bing 搜索返回 HTTP {}", response.statusCode());
				return results;
			}

			JsonNode root = objectMapper.readTree(response.body());
			JsonNode webPages = root.path("webPages").path("value");

			if (webPages.isArray()) {
				for (JsonNode page : webPages) {
					if (results.size() >= maxResults) {
						break;
					}
					String title = page.path("name").asText("");
					String snippet = page.path("snippet").asText("");
					String url = page.path("url").asText("");

					if (title.isEmpty() || url.isEmpty()) {
						continue;
					}
					if (snippet.length() > 300) {
						snippet = snippet.substring(0, 297) + "...";
					}
					results.add(new SearchResult(title, snippet, url));
				}
			}

			log.info("Bing 搜索完成: query='{}', 结果数={}", query, results.size());
		}
		catch (Exception e) {
			log.warn("Bing 搜索失败: query='{}', error={}", query, e.getMessage());
		}
		return results;
	}
}
