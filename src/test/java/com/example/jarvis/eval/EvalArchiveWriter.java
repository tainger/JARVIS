package com.example.jarvis.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 归档器：评测结束写 docs/eval/history/&lt;UTC日期&gt;-&lt;git短哈希&gt;-&lt;序号&gt;/{summary.json,report.md}。
 * summary.json 结构即 {@link EvalRunSummary}，可被 Jackson 回读。
 */
public class EvalArchiveWriter {

	private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

	private final Path historyDir;
	private final ObjectMapper json = new ObjectMapper();

	public EvalArchiveWriter(Path historyDir) {
		this.historyDir = historyDir;
	}

	/** 组装一次运行的 summary（runId/timestamp/gitCommit 在此填充）。latency 可为空 Map（时延分位随指标深度任务补齐）。 */
	public EvalRunSummary buildSummary(String suite, Map<String, Integer> caseCounts,
			Map<String, Double> metrics, Map<String, Double> latency, Map<String, Object> config) throws IOException {
		EvalRunSummary s = new EvalRunSummary();
		s.suite = suite;
		s.runId = nextRunId();
		s.timestamp = Instant.now().toString();
		s.gitCommit = gitHash(true);
		s.caseCounts = caseCounts;
		s.metrics = metrics;
		s.latency = latency;
		s.config = config;
		return s;
	}

	/** 落盘 summary.json + report.md，返回归档目录。 */
	public Path write(EvalRunSummary summary, String reportMd) throws IOException {
		Path dir = historyDir.resolve(summary.runId);
		Files.createDirectories(dir);
		json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(), summary);
		Files.writeString(dir.resolve("report.md"), reportMd);
		return dir;
	}

	/** runId = &lt;UTC日期&gt;-&lt;git短哈希&gt;-&lt;两位序号&gt;；同日同 commit 递增。 */
	String nextRunId() throws IOException {
		String prefix = UTC_DAY.format(Instant.now()) + "-" + gitHash(false) + "-";
		long seq = 1;
		if (Files.isDirectory(historyDir)) {
			try (var stream = Files.list(historyDir)) {
				seq = stream.filter(Files::isDirectory)
						.map(p -> p.getFileName().toString())
						.filter(n -> n.startsWith(prefix))
						.map(n -> n.substring(prefix.length()))
						.map(s -> {
							try {
								return Integer.parseInt(s);
							}
							catch (NumberFormatException e) {
								return 0;
							}
						})
						.max(Long::compare).orElse(0) + 1;
			}
		}
		return prefix + "%02d".formatted(seq);
	}

	/** git 哈希；沙箱/CI 外部环境不可用时降级 nogit（评测照跑，仅归档可读性下降）。 */
	private String gitHash(boolean full) throws IOException {
		List<String> cmd = full
				? List.of("git", "rev-parse", "HEAD")
				: List.of("git", "rev-parse", "--short", "HEAD");
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out;
			try (var in = p.getInputStream()) {
				out = new String(in.readAllBytes()).trim();
			}
			if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) {
				return out;
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			// fall through to nogit
		}
		return "nogit";
	}
}
