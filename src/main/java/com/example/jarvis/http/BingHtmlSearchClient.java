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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bing HTML 搜索后端（无需 API Key）。
 * 通过解析 Bing 搜索结果页提取标题+摘要+URL，免费可用。
 */
@Component
public class BingHtmlSearchClient implements WebSearchClient {

	private static final Logger log = LoggerFactory.getLogger(BingHtmlSearchClient.class);

	private static final int HTTP_TIMEOUT_SECONDS = 10;

	private static final String BING_URL = "https://www.bing.com/search";

	private static final String UA =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

	// Bing 结果块：<li class="b_algo">...</li>
	private static final Pattern RESULT_BLOCK = Pattern.compile(
			"<li class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL);

	// 标题+URL：<h2><a href="URL">TITLE</a>
	private static final Pattern TITLE_URL = Pattern.compile(
			"<h2[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

	// 摘要：<p>SNIPPET</p> 或 <div class="b_caption"><p>SNIPPET</p>
	private static final Pattern SNIPPET = Pattern.compile(
			"<p[^>]*>(.*?)</p>", Pattern.DOTALL);

	private final HttpClient httpClient;

	public BingHtmlSearchClient() {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.build();
		log.info("Bing HTML 搜索后端已初始化");
	}

	@Override
	public List<SearchResult> search(String query, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		try {
			String url = BING_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
					+ "&count=" + Math.min(maxResults * 2, 20);

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", UA)
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
					.header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
					.timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("Bing HTML 搜索返回 HTTP {}", response.statusCode());
				return results;
			}

			String html = response.body();
			results = parseResults(html, maxResults);

			log.info("Bing HTML 搜索完成: query='{}', 结果数={}", query, results.size());
		}
		catch (Exception e) {
			log.warn("Bing HTML 搜索失败: query='{}', error={}", query, e.getMessage());
		}
		return results;
	}

	private List<SearchResult> parseResults(String html, int maxResults) {
		List<SearchResult> results = new ArrayList<>();

		Matcher blockMatcher = RESULT_BLOCK.matcher(html);
		while (blockMatcher.find() && results.size() < maxResults) {
			String block = blockMatcher.group(1);

			// 提取标题和 URL
			Matcher tuMatcher = TITLE_URL.matcher(block);
			if (!tuMatcher.find()) {
				continue;
			}

			String rawUrl = tuMatcher.group(1);
			String title = cleanHtml(tuMatcher.group(2));

			// Bing 的 URL 可能是重定向链接，尝试提取真实 URL
			String url = extractBingRedirectUrl(rawUrl);

			if (title.isEmpty() || !isSafeUrl(url)) {
				continue;
			}

			// 提取摘要
			String snippet = "";
			Matcher snMatcher = SNIPPET.matcher(block);
			if (snMatcher.find()) {
				snippet = cleanHtml(snMatcher.group(1));
			}
			if (snippet.isEmpty()) {
				snippet = "(无摘要)";
			}
			if (snippet.length() > 300) {
				snippet = snippet.substring(0, 297) + "...";
			}

			results.add(new SearchResult(title, snippet, url));
		}

		return results;
	}

	/**
	 * Bing 搜索结果中的 URL 可能是 Bing 内部重定向链接。
	 * 尝试从重定向参数中提取真实 URL；如果无法提取，返回原始链接。
	 */
	private String extractBingRedirectUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return "";
		}
		String url = rawUrl.trim();

		// Bing 重定向格式：https://www.bing.com/ck/a?...&u=a1aHR0c...
		// u 参数是 base64 编码的真实 URL
		if (url.contains("/ck/a?") && url.contains("u=")) {
			int uIdx = url.indexOf("u=");
			if (uIdx >= 0) {
				String encoded = url.substring(uIdx + 2);
				int ampIdx = encoded.indexOf('&');
				if (ampIdx >= 0) {
					encoded = encoded.substring(0, ampIdx);
				}
				try {
					// Bing 使用 base64 编码，前缀 "a1" 表示版本
					if (encoded.startsWith("a1")) {
						encoded = encoded.substring(2);
					}
					byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
					String realUrl = new String(decoded, StandardCharsets.UTF_8);
					if (realUrl.startsWith("http://") || realUrl.startsWith("https://")) {
						return realUrl;
					}
				}
				catch (Exception e) {
					// 解码失败，返回原始链接
				}
			}
		}

		// 相对协议补全
		if (url.startsWith("//")) {
			url = "https:" + url;
		}

		return url;
	}

	private String cleanHtml(String raw) {
		if (raw == null) {
			return "";
		}
		String text = raw.replaceAll("<[^>]+>", "");
		text = text.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&nbsp;", " ")
				.replace("&#0183;", "·")
				.replace("&#0160;", " ")
				.replace("&#0153;", "™");
		text = text.replaceAll("[\\x00-\\x1f\\x7f]", "");
		return text.trim();
	}

	private boolean isSafeUrl(String url) {
		return url != null
				&& !url.isEmpty()
				&& (url.startsWith("http://") || url.startsWith("https://"))
				&& !url.toLowerCase().contains("javascript:")
				&& !url.toLowerCase().contains("data:");
	}
}
