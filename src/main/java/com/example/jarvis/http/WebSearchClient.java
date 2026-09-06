package com.example.jarvis.http;

import java.util.List;

/** 搜索客户端抽象接口，由 DuckDuckGo / Bing 等后端实现。 */
public interface WebSearchClient {

	List<SearchResult> search(String query, int maxResults);
}
