package com.example.jarvis.eval;

/**
 * 生成层忠实度裁判契约（LLM-as-judge）。DeepSeek 实现随指标深度任务提供。
 */
public interface EvalJudge {

	/** 单条裁决：score 1-5，reason 一句话说明。 */
	record FaithVerdict(int score, String reason) {
	}

	/** 裁判模型标识（写入归档 config 快照）。 */
	String model();

	/**
	 * 判断 answer 是否仅由 snippets 支撑（忠实度）。
	 *
	 * @return 裁决；解析失败重试后仍失败时返回 null（不计入平均分）
	 */
	FaithVerdict judge(String answer, String snippets) throws Exception;
}
