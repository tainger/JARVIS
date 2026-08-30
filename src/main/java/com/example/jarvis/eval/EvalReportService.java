package com.example.jarvis.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.jarvis.rag.RagProperties;

/**
 * 评测中心只读服务：读归档目录，输出历史列表与单次详情（含与前一次同 suite 的指标 diff）。
 * 目录缺失/为空不抛错——前端据此渲染空状态引导。
 */
@Service
public class EvalReportService {

	private final EvalHistoryStore store;

	public EvalReportService(RagProperties props) {
		this.store = new EvalHistoryStore(Path.of(props.getEval().getHistoryDir()));
	}

	/** 全部归档，runId 升序（字典序即时间序），趋势图可直接用。 */
	public List<EvalRunSummary> list() throws IOException {
		return store.listAll();
	}

	/**
	 * 单次详情：summary + 与前一次同 suite 归档的指标 diff。
	 * diff 为 null 表示首次运行（基线首次建立）。
	 */
	public Optional<Detail> detail(String runId) throws IOException {
		EvalRunSummary summary = store.find(runId).orElse(null);
		if (summary == null) {
			return Optional.empty();
		}
		EvalRunSummary prev = store.previousOf(summary).orElse(null);
		return Optional.of(new Detail(summary, prev, prev == null ? null : diff(prev, summary)));
	}

	/** 指标逐项 diff：仅对比两次共有的指标键。 */
	private Map<String, Object> diff(EvalRunSummary prev, EvalRunSummary current) {
		LinkedHashMap<String, Object> out = new LinkedHashMap<>();
		out.put("prevRunId", prev.runId);
		LinkedHashMap<String, Object> items = new LinkedHashMap<>();
		current.metrics.forEach((k, v) -> {
			Double pv = prev.metrics.get(k);
			if (pv == null) {
				return;
			}
			double d = v - pv;
			LinkedHashMap<String, Object> item = new LinkedHashMap<>();
			item.put("prev", pv);
			item.put("current", v);
			item.put("delta", d);
			item.put("verdict", EvalTrendDiff.verdict(k, d));
			item.put("significant", EvalTrendDiff.isSignificant(d));
			items.put(k, item);
		});
		out.put("metrics", items);
		return out;
	}

	/** 详情 = summary + 前一次引用 + diff。 */
	public record Detail(EvalRunSummary summary, EvalRunSummary previous, Map<String, Object> diff) {
	}
}
