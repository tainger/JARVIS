package com.example.jarvis.http;

/** 搜索结果记录，统一由各搜索后端实现返回。 */
public record SearchResult(String title, String snippet, String url) {
}
