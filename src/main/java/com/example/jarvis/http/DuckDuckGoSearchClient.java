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
 * DuckDuckGo 搜索后端。
 * 免费、无需 API Key，通过解析 HTML 搜索结果页提取标题+摘要+URL。
 * 依次尝试 Lite 端点 → HTML 端点 → DuckDuckGo API，应对反爬限流。
 */
@Component
public class DuckDuckGoSearchClient implements WebSearchClient {

	private static final Logger log = LoggerFactory.getLogger(DuckDuckGoSearchClient.class);

	private static final int HTTP_TIMEOUT_SECONDS = 10;

	private static final String LITE_URL = "https://lite.duckduckgo.com/lite/";
	private static final String HTML_URL = "https://html.duckduckgo.com/html/";
	private static final String API_URL = "https://api.duckduckgo.com/";

	private static final String UA =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

	// ── Lite 页面正则 ──
	private static final Pattern LITE_RESULT = Pattern.compile(
			"<a[^>]+class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"
					+ ".*?<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
			Pattern.DOTALL);

	// ── HTML 页面正则 ──
	private static final Pattern HTML_FALLBACK = Pattern.compile(
			"<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?"
					+ "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
			Pattern.DOTALL);

	private static final Pattern HTML_TITLE = Pattern.compile(
			"<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL);

	private static final Pattern HTML_URL_PATTERN = Pattern.compile(
			"<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"", Pattern.DOTALL);

	private static final Pattern HTML_SNIPPET = Pattern.compile(
			"<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

	private final HttpClient httpClient;

	public DuckDuckGoSearchClient(WebSearchConfig config) {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.build();
		log.info("DuckDuckGo 搜索后端已初始化");
	}

	@Override
	public List<SearchResult> search(String query, int maxResults) {
		List<SearchResult> results;

		// 1. 尝试 Lite 端点（反爬最宽松）
		results = searchLite(query, maxResults);
		if (!results.isEmpty()) {
			log.info("DuckDuckGo Lite 搜索完成: query='{}', 结果数={}", query, results.size());
			return results;
		}

		// 2. 尝试 HTML 端点
		results = searchHtml(query, maxResults);
		if (!results.isEmpty()) {
			log.info("DuckDuckGo HTML 搜索完成: query='{}', 结果数={}", query, results.size());
			return results;
		}

		// 3. 尝试 Instant Answer API（结果有限但稳定）
		results = searchApi(query, maxResults);
		if (!results.isEmpty()) {
			log.info("DuckDuckGo API 搜索完成: query='{}', 结果数={}", query, results.size());
			return results;
		}

		log.warn("DuckDuckGo 搜索无结果: query='{}'", query);
		return new ArrayList<>();
	}

	private List<SearchResult> searchLite(String query, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		try {
			String formBody = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
					+ "&kl=us-en";

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(LITE_URL))
					.header("User-Agent", UA)
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
					.header("Accept-Language", "en-US,en;q=0.9")
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header("Referer", "https://lite.duckduckgo.com/")
					.timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
					.POST(HttpRequest.BodyPublishers.ofString(formBody))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.debug("DuckDuckGo Lite 返回 HTTP {}", response.statusCode());
				return results;
			}

			String html = response.body();

			// Lite 页面解析：链接在 <a class="result-link"> 中
			Matcher m = LITE_RESULT.matcher(html);
			while (m.find() && results.size() < maxResults) {
				String url = m.group(1).trim();
				String title = cleanHtml(m.group(2));
				String snippet = cleanHtml(m.group(3));

				// Lite 的 URL 可能是直接链接
				if (url.startsWith("//")) {
					url = "https:" + url;
				}
				if (title.isEmpty() || !isSafeUrl(url)) {
					continue;
				}
				if (snippet.isEmpty()) {
					snippet = "(无摘要)";
				}
				if (snippet.length() > 300) {
					snippet = snippet.substring(0, 297) + "...";
				}
				results.add(new SearchResult(title, snippet, url));
			}

			// Lite fallback：如果正则没匹配到，尝试更宽松的链接提取
			if (results.isEmpty()) {
				results.addAll(parseLiteLinks(html, maxResults));
			}
		}
		catch (Exception e) {
			log.debug("DuckDuckGo Lite 搜索异常: {}", e.getMessage());
		}
		return results;
	}

	private List<SearchResult> parseLiteLinks(String html, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		// Lite 页面的结果链接在 <a rel="nofollow" class="result-link" href="...">
		Pattern linkPat = Pattern.compile(
				"<a[^>]*rel=\"nofollow\"[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
				Pattern.DOTALL);
		Matcher lm = linkPat.matcher(html);
		while (lm.find() && results.size() < maxResults) {
			String url = lm.group(1);
			String title = cleanHtml(lm.group(2));
			if (title.isEmpty() || !isSafeUrl(url)) {
				continue;
			}
			results.add(new SearchResult(title, "(无摘要)", url));
		}
		return results;
	}

	private List<SearchResult> searchHtml(String query, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		try {
			String formBody = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
					+ "&kl=cn-zh";

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(HTML_URL))
					.header("User-Agent", UA)
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
					.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header("Referer", "https://html.duckduckgo.com/")
					.timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
					.POST(HttpRequest.BodyPublishers.ofString(formBody))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.debug("DuckDuckGo HTML 返回 HTTP {}", response.statusCode());
				return results;
			}

			String html = response.body();

			// 主解析
			Matcher m = HTML_FALLBACK.matcher(html);
			while (m.find() && results.size() < maxResults) {
				String rawUrl = m.group(1);
				String title = cleanHtml(m.group(2));
				String snippet = cleanHtml(m.group(3));
				String url = extractRedirectUrl(rawUrl);

				if (title.isEmpty() || !isSafeUrl(url)) {
					continue;
				}
				if (snippet.isEmpty()) {
					snippet = "(无摘要)";
				}
				if (snippet.length() > 300) {
					snippet = snippet.substring(0, 297) + "...";
				}
				results.add(new SearchResult(title, snippet, url));
			}

			// 简单模式 fallback
			if (results.isEmpty()) {
				Matcher tm = HTML_TITLE.matcher(html);
				Matcher um = HTML_URL_PATTERN.matcher(html);
				Matcher sm = HTML_SNIPPET.matcher(html);
				while (tm.find() && um.find() && sm.find() && results.size() < maxResults) {
					String title = cleanHtml(tm.group(1));
					String url = extractRedirectUrl(um.group(1));
					String snippet = cleanHtml(sm.group(1));
					if (title.isEmpty() || !isSafeUrl(url)) {
						continue;
					}
					if (snippet.isEmpty()) {
						snippet = "(无摘要)";
					}
					if (snippet.length() > 300) {
						snippet = snippet.substring(0, 297) + "...";
					}
					results.add(new SearchResult(title, snippet, url));
				}
			}
		}
		catch (Exception e) {
			log.debug("DuckDuckGo HTML 搜索异常: {}", e.getMessage());
		}
		return results;
	}

	private List<SearchResult> searchApi(String query, int maxResults) {
		List<SearchResult> results = new ArrayList<>();
		try {
			String url = API_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
					+ "&format=json&no_html=1&skip_disambig=0";

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", UA)
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.debug("DuckDuckGo API 返回 HTTP {}", response.statusCode());
				return results;
			}

			String json = response.body();

			// 解析 JSON 中的 AbstractText 和 AbstractURL
			String abstractText = extractJsonField(json, "AbstractText");
			String abstractUrl = extractJsonField(json, "AbstractURL");
			String heading = extractJsonField(json, "Heading");

			if (!abstractText.isEmpty()) {
				String title = heading.isEmpty() ? query : heading;
				if (!abstractUrl.isEmpty() && isSafeUrl(abstractUrl)) {
					String snippet = abstractText.length() > 300
							? abstractText.substring(0, 297) + "..."
							: abstractText;
					results.add(new SearchResult(title, snippet, abstractUrl));
				}
			}

			// 解析 RelatedTopics
			Pattern rtPattern = Pattern.compile(
					"\"FirstURL\"\\s*:\\s*\"([^\"]+)\".*?\"Text\"\\s*:\\s*\"([^\"]*)\"",
					Pattern.DOTALL);
			Matcher rtm = rtPattern.matcher(json);
			while (rtm.find() && results.size() < maxResults) {
				String url = rtm.group(1);
				String text = cleanJsonString(rtm.group(2));
				if (url.isEmpty() || text.isEmpty() || !isSafeUrl(url)) {
					continue;
				}
				String title = text.length() > 80 ? text.substring(0, 77) + "..." : text;
				String snippet = text.length() > 300 ? text.substring(0, 297) + "..." : text;
				results.add(new SearchResult(title, snippet, url));
			}
		}
		catch (Exception e) {
			log.debug("DuckDuckGo API 搜索异常: {}", e.getMessage());
		}
		return results;
	}

	private String extractJsonField(String json, String field) {
		Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
		Matcher m = p.matcher(json);
		if (m.find()) {
			return cleanJsonString(m.group(1));
		}
		return "";
	}

	private String cleanJsonString(String s) {
		return s.replace("\\/", "/")
				.replace("\\n", " ")
				.replace("\\\"", "\"")
				.replace("\\\\", "\\")
				.trim();
	}

	/** DuckDuckGo 的跳转链接格式为 //duckduckgo.com/l/?uddg=ENCODED_URL，需解码提取真实 URL */
	private String extractRedirectUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return "";
		}
		String url = rawUrl.trim();

		if (url.contains("uddg=")) {
			int idx = url.indexOf("uddg=");
			String encoded = url.substring(idx + 5);
			int ampIdx = encoded.indexOf('&');
			if (ampIdx >= 0) {
				encoded = encoded.substring(0, ampIdx);
			}
			try {
				return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
			}
			catch (Exception e) {
				return "";
			}
		}

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
				.replace("&nbsp;", " ");
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
