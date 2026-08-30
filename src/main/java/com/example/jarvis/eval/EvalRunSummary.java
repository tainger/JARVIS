package com.example.jarvis.eval;

import java.util.List;
import java.util.Map;

/**
 * 归档 summary：docs/eval/history/&lt;runId&gt;/summary.json 的结构契约。
 * 由测试侧 EvalArchiveWriter 写入、主侧 EvalHistoryStore（只读 API）回读，字段双方共享。
 * 公有字段即 Jackson 映射（无 getter），保持与历史归档文件格式一致。
 */
public class EvalRunSummary {

	public String suite;
	public String runId;
	public String timestamp;
	public String gitCommit;
	public Map<String, Integer> caseCounts;
	public Map<String, Double> metrics;
	public Map<String, Double> latency;

	/**
	 * 逐条用例记录（评测中心明细表数据源）：检索层为
	 * {id,type,question,expectDoc,relevantRank,top1Score,injected,missDetail,latencyMs}，
	 * 生成层为 {id,type,question,keywordsOk,citationNote,faithScore,faithReason,e2eMs}。
	 * 用 Map 而非强类型 record：两个 suite 字段形状不同，弱类型避免两套模型。
	 */
	public List<Map<String, Object>> cases = List.of();

	public Map<String, Object> config;
}
