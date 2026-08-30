package com.example.jarvis.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.jarvis.eval.EvalReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测中心只读历史 API：读 docs/eval/history 归档（测试侧 EvalArchiveWriter 写入）。
 * 鉴权沿用 SecurityConfig（/api/** 需登录）。
 */
@RestController
@RequestMapping("/api/knowledge/eval/history")
public class EvalReportController {

	/** runId 白名单：日期-短哈希-序号 / nogit 变体，防路径拼接类注入。 */
	private static final String RUN_ID_PATTERN = "[A-Za-z0-9_-]{1,64}(\\.[A-Za-z0-9_-]{1,10})?";

	private final EvalReportService service;

	public EvalReportController(EvalReportService service) {
		this.service = service;
	}

	/** 历史列表（runId 升序即时间序）；目录缺失/为空返回空数组。 */
	@GetMapping
	public Map<String, Object> list() throws Exception {
		LinkedHashMap<String, Object> resp = new LinkedHashMap<>();
		resp.put("items", service.list());
		return resp;
	}

	/** 单次详情：完整 summary + 与前一次同 suite 的 diff（首次运行 diff=null）。 */
	@GetMapping("/{runId}")
	public ResponseEntity<Object> detail(@PathVariable String runId) throws Exception {
		if (!runId.matches(RUN_ID_PATTERN)) {
			return ResponseEntity.badRequest().body(Map.of("error", "非法 runId"));
		}
		var d = service.detail(runId).orElse(null);
		if (d == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", "归档不存在: " + runId));
		}
		// LinkedHashMap：diff 允许为 null（Map.of 会 NPE）
		LinkedHashMap<String, Object> resp = new LinkedHashMap<>();
		resp.put("summary", d.summary());
		resp.put("previous", d.previous());
		resp.put("diff", d.diff());
		return ResponseEntity.ok(resp);
	}
}
