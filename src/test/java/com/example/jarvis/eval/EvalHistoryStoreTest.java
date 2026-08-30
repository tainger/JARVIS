package com.example.jarvis.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalHistoryStore 契约：目录缺失/为空返回空、按 runId 升序、latest 按 suite 过滤、损坏归档跳过。
 */
class EvalHistoryStoreTest {

	@TempDir
	Path tempDir;

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void emptyWhenDirMissing() throws IOException {
		EvalHistoryStore store = new EvalHistoryStore(tempDir.resolve("not-exist"));
		assertTrue(store.listAll().isEmpty());
		assertTrue(store.latest("retrieval").isEmpty());
	}

	@Test
	void emptyWhenDirEmpty() throws IOException {
		EvalHistoryStore store = new EvalHistoryStore(tempDir);
		assertTrue(store.listAll().isEmpty());
		assertTrue(store.latest("retrieval").isEmpty());
	}

	@Test
	void readsSortedAndLatestBySuiteSkippingBroken() throws IOException {
		write("20260829-aaa-01", "retrieval", 0.9);
		write("20260830-bbb-01", "retrieval", 1.0);
		write("20260830-bbb-02", "generation", 0.8);
		Files.createDirectories(tempDir.resolve("20260828-broken-01")); // 无 summary.json，应跳过

		EvalHistoryStore store = new EvalHistoryStore(tempDir);
		List<EvalRunSummary> all = store.listAll();
		assertEquals(3, all.size());
		assertEquals("20260829-aaa-01", all.get(0).runId);
		assertEquals("20260830-bbb-02", all.get(2).runId);

		assertEquals(1.0, store.latest("retrieval").orElseThrow().metrics.get("recallAt4"));
		assertEquals("generation", store.latest("generation").orElseThrow().suite);
		assertTrue(store.latest("unknown").isEmpty());
	}

	private void write(String runId, String suite, double recall) throws IOException {
		EvalRunSummary s = new EvalRunSummary();
		s.suite = suite;
		s.runId = runId;
		s.metrics = Map.of("recallAt4", recall);
		Path dir = tempDir.resolve(runId);
		Files.createDirectories(dir);
		json.writeValue(dir.resolve("summary.json").toFile(), s);
	}
}
