package com.example.jarvis.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.jarvis.mapper.EvalCandidateMapper;
import com.example.jarvis.model.EvalCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 评测候选池：入池查重、triage（转正/丢弃）。
 * 转正会把补全的标注 case 追加进 src/test/resources/rag-eval-cases.json，
 * 并回读校验（合法且用例数 +1）后才落 promoted 状态。
 */
@Service
public class EvalCandidateService {

	private static final Logger log = LoggerFactory.getLogger(EvalCandidateService.class);

	/** 标注集路径（相对后端工作目录 = 项目根；容器形态不可写，转正前前端需禁用）。 */
	static final Path CASES_FILE = Path.of("src", "test", "resources", "rag-eval-cases.json");

	/** 规范化：去空白与中英文标点、统一小写——"餐补多少？" 与 "餐补 多少" 视为同题。 */
	private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+");

	private final EvalCandidateMapper mapper;
	private final ObjectMapper json = new ObjectMapper();

	public EvalCandidateService(EvalCandidateMapper mapper) {
		this.mapper = mapper;
	}

	public static String normalize(String question) {
		return NOISE.matcher(question).replaceAll("").toLowerCase();
	}

	/** 入池：必填校验由控制器做；pending 查重命中返回既有条目（409 语义）。 */
	public EvalCandidate findPendingDuplicate(String question) {
		return mapper.findPendingByNorm(normalize(question));
	}

	public EvalCandidate submit(String question, String note, String source, String chatRef) {
		EvalCandidate c = new EvalCandidate();
		c.setQuestion(question.trim());
		c.setQuestionNorm(normalize(question));
		c.setNote(note);
		c.setSource(source == null || source.isBlank() ? "chat" : source);
		c.setChatRef(chatRef);
		c.setStatus("pending");
		mapper.insert(c);
		return c;
	}

	public record Page(List<EvalCandidate> items, long total) {
	}

	public Page list(String status, int page, int size) {
		String st = status == null || status.isBlank() ? "pending" : status;
		int p = Math.max(page, 1);
		int s = Math.min(Math.max(size, 1), 100);
		return new Page(mapper.findByStatus(st, (p - 1) * s, s), mapper.countByStatus(st));
	}

	public record PromoteResult(long candidateId, String caseId) {
	}

	/**
	 * 转正：补全标注 → 预检标注集可写 → CAS 置 promoted → 原子追加写 → 回读校验。
	 * 顺序保证：写文件失败不会留下半转正；CAS 失败（并发 triage）也不会重复追加。
	 *
	 * @throws IllegalStateException 候选已被 triage（控制器转 409）
	 * @throws IOException           标注集不可读写（容器形态）
	 */
	public PromoteResult promote(long id, String type, String expectDoc,
			List<String> expectChunkKeywords, List<String> expectAnswerKeywords) throws IOException {
		EvalCandidate c = mapper.findById(id);
		if (c == null) {
			throw new IllegalArgumentException("候选不存在：" + id);
		}
		if (!"pending".equals(c.getStatus())) {
			throw new IllegalStateException("该候选已被处理（" + c.getStatus() + "）");
		}

		String caseId = "user-" + id;
		Map<String, Object> newCase = new LinkedHashMap<>();
		newCase.put("id", caseId);
		newCase.put("type", type);
		newCase.put("question", c.getQuestion());
		newCase.put("expectDoc", expectDoc);
		if (expectChunkKeywords != null && !expectChunkKeywords.isEmpty()) {
			newCase.put("expectChunkKeywords", expectChunkKeywords);
		}
		if (expectAnswerKeywords != null && !expectAnswerKeywords.isEmpty()) {
			newCase.put("expectAnswerKeywords", expectAnswerKeywords);
		}

		// 1. 预检：可写 + 无重复 id + 组装新用例集（尚未落盘）
		int before = 0;
		List<Map<String, Object>> cases = new ArrayList<>();
		if (Files.exists(CASES_FILE)) {
			cases = json.readValue(Files.readString(CASES_FILE),
					new TypeReference<List<Map<String, Object>>>() { });
			before = cases.size();
			if (cases.stream().anyMatch(x -> caseId.equals(x.get("id")))) {
				throw new IllegalStateException("标注集已存在同 id 用例：" + caseId);
			}
		}
		if (!Files.exists(CASES_FILE) ? !Files.isWritable(CASES_FILE.getParent())
				: !Files.isWritable(CASES_FILE)) {
			throw new IOException("标注集不可写（容器形态请挂载 src/test/resources 或改为本地运行）：" + CASES_FILE);
		}
		cases.add(newCase);

		// 2. CAS：只对 pending 生效，0 行 = 并发下已被处理
		if (mapper.markPromoted(id, expectDoc) == 0) {
			throw new IllegalStateException("该候选已被处理（并发）");
		}

		// 3. 原子写 + 回读校验
		Path tmp = CASES_FILE.resolveSibling(CASES_FILE.getFileName() + ".tmp");
		json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cases);
		Files.move(tmp, CASES_FILE, StandardCopyOption.REPLACE_EXISTING);
		List<Map<String, Object>> verify = json.readValue(Files.readString(CASES_FILE),
				new TypeReference<List<Map<String, Object>>>() { });
		if (verify.size() != before + 1 || verify.stream().noneMatch(x -> caseId.equals(x.get("id")))) {
			throw new IOException("标注集回读校验失败，转正中止（请检查 " + CASES_FILE + "）");
		}
		log.info("标注集新增用例 {}（{} 条 → {} 条）", caseId, before, verify.size());
		return new PromoteResult(id, caseId);
	}

	public void discard(long id) {
		if (mapper.findById(id) == null) {
			throw new IllegalArgumentException("候选不存在：" + id);
		}
		if (mapper.markDiscarded(id) == 0) {
			throw new IllegalStateException("该候选已被处理（promoted/discarded）");
		}
	}
}
