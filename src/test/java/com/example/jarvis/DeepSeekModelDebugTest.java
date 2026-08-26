package com.example.jarvis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 独立的调试用测试（不加载 Spring 容器），用于直连 DeepSeek 官方 API
 * （https://api-docs.deepseek.com/zh-cn/）验证具体模型是否可用。
 *
 * <p>本测试聚焦排查模型名 {@code deepseek-v4-flash-vision-exp}：官方文档不同页面
 * 对该模型是否存在存在分歧，因此测试会先动态拉取 /models 列出真实可用模型，
 * 再对目标模型发起一次实际对话调用，把结果/错误打印出来便于调试。</p>
 *
 * <p>运行方式（需要真实密钥，否则自动跳过，不会失败）：</p>
 * <pre>
 *   export DEEPSEEK_API_KEY=sk-你的密钥
 *   ./mvnw -Dtest=DeepSeekModelDebugTest test
 * </pre>
 */
class DeepSeekModelDebugTest {

	/** OpenAI 兼容 base-url（AgentScope 会自动拼接 /chat/completions）。 */
	private static final String BASE_URL = "https://api.deepseek.com/v1";

	/** 本次要调试的目标模型名。 */
	private static final String TARGET_MODEL = "deepseek-v4-flash-vision-exp";

	/** 未配置密钥时跳过，避免污染 CI；配置后才真正联网调试。 */
	private static String requireApiKey() {
		String key = "sk-17d3ecb0643a45058f08d6fbf08261db";
		Assumptions.assumeTrue(key != null && !key.isBlank(),
				"未设置环境变量 DEEPSEEK_API_KEY，跳过 DeepSeek 联网调试测试");
		return key;
	}

	@Test
	@DisplayName("列出 DeepSeek 官方真实可用模型（/models）")
	void listAvailableModels() throws Exception {
		String apiKey = requireApiKey();

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create("https://api.deepseek.com/models"))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", "application/json")
				.GET()
				.build();

		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

		System.out.println("===== GET /models 状态码: " + resp.statusCode() + " =====");
		System.out.println(resp.body());
		System.out.println("目标模型 [" + TARGET_MODEL + "] 是否出现在列表中: "
				+ resp.body().contains(TARGET_MODEL));
	}

	@Test
	@DisplayName("实际调用目标模型 deepseek-v4-flash-vision-exp 做一次对话")
	void callTargetModel() {
		String apiKey = requireApiKey();

		OpenAIChatModel model = OpenAIChatModel.builder()
				.apiKey(apiKey)
				.baseUrl(BASE_URL)
				.modelName(TARGET_MODEL)
				.stream(false)
				.build();

		Msg input = Msg.builder()
				.role(MsgRole.USER)
				.textContent("用一句话介绍你自己，并说明你是否支持图片输入。")
				.build();

		System.out.println("===== 调用模型: " + model.getModelName() + " @ " + BASE_URL + " =====");
		try {
			// AgentScope 的模型调用为流式 Flux，这里聚合成完整响应后取文本。
			ChatResponse last = model.stream(List.of(input), List.of(), GenerateOptions.builder().build())
					.blockLast();

			if (last == null) {
				System.out.println("模型无返回内容（响应为空）。");
				return;
			}

			String text = last.getContent().stream()
					.filter(TextBlock.class::isInstance)
					.map(TextBlock.class::cast)
					.map(TextBlock::getText)
					.reduce("", String::concat);

			System.out.println("finishReason = " + last.getFinishReason());
			System.out.println("模型回复    = " + text);
			System.out.println("usage       = " + last.getUsage());
		} catch (Exception e) {
			// 模型不存在 / 无权限 / 网络问题时打印完整错误，便于定位是模型名问题还是别的。
			System.out.println("调用失败: " + e.getClass().getSimpleName() + " -> " + e.getMessage());
			throw e;
		}
	}
}
