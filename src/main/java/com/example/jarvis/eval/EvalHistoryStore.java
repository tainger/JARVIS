package com.example.jarvis.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 历史归档读取：按 runId 升序读全部 summary（runId 含 UTC 日期，字典序即时间序）。
 * 目录不存在或为空一律返回空集合——评测中心与 diff 都要容忍"首次运行无历史"。
 * 写入方是测试侧的 EvalArchiveWriter；本类主侧只读（评测中心 API）。
 */
public class EvalHistoryStore {

	private final Path historyDir;
	private final ObjectMapper json = new ObjectMapper();

	public EvalHistoryStore(Path historyDir) {
		this.historyDir = historyDir;
	}

	public Path getHistoryDir() {
		return historyDir;
	}

	/** 全部归档 summary；损坏目录跳过不抛出。 */
	public List<EvalRunSummary> listAll() throws IOException {
		if (!Files.isDirectory(historyDir)) {
			return List.of();
		}
		List<EvalRunSummary> out = new ArrayList<>();
		try (var stream = Files.list(historyDir)) {
			stream.filter(Files::isDirectory).sorted().forEach(dir -> {
				Path f = dir.resolve("summary.json");
				if (Files.isRegularFile(f)) {
					try {
						out.add(json.readValue(f.toFile(), EvalRunSummary.class));
					}
					catch (IOException e) {
						// 损坏归档跳过，不影响其余历史
					}
				}
			});
		}
		return out;
	}

	/** 指定 suite 的最近一次归档（无历史返回 empty）。 */
	public Optional<EvalRunSummary> latest(String suite) throws IOException {
		return listAll().stream().filter(s -> suite.equals(s.suite)).reduce((a, b) -> b);
	}

	/** 按 runId 精确查找（无则 empty）。 */
	public Optional<EvalRunSummary> find(String runId) throws IOException {
		return listAll().stream().filter(s -> runId.equals(s.runId)).findFirst();
	}

	/** runId 在归档序列中的前一次同 suite 归档（无则 empty）。 */
	public Optional<EvalRunSummary> previousOf(EvalRunSummary current) throws IOException {
		List<EvalRunSummary> all = listAll();
		EvalRunSummary prev = null;
		for (EvalRunSummary s : all) {
			if (current.runId.equals(s.runId)) {
				return Optional.ofNullable(prev).filter(p -> p.suite.equals(current.suite));
			}
			if (current.suite.equals(s.suite)) {
				prev = s;
			}
		}
		return Optional.empty();
	}
}
