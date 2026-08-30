## Context

现状：`RagEvalTest` 一个测试类承担全部评测（44 条用例 JSON + 检索层/生成层两个用例方法），报告写 `target/`，手动触发，指标 6 项。后端 Spring Boot 4 + MySQL + Ollama(bge-m3)，前端 React + antd（黏土风主题），仓库远端 github.com/tainger/JARVIS。openspec config 要求 artifacts 用中文、结构化标题与 SHALL/MUST 关键词保留英文。

## Goals / Non-Goals

**Goals:**
- 评测运行内核可复用（测试、钩子、CI 同一入口）
- 每次运行可追溯、相邻运行可对比
- bad case 从使用现场流回标注集
- 指标覆盖忠实度与时延，报表分层可定位弱语料
- 浏览器内完成"看结果 → triage case"

**Non-Goals:**
- 不改变检索/注入/chat 任何运行时行为
- 不做在线（请求时）质量监控与采样评测（属未来能力）
- 不做多模型排行榜式评测框架
- 不引入时序数据库/图表后端，前端图表直接读归档 JSON

## Decisions

1. **评测内核拆到 test 源码集**：新增 `src/test/java/com/example/jarvis/eval/` 包（`EvalRunner` 编排、`EvalArchiveWriter` 归档、`EvalJudge` 裁判、`EvalReportRenderer` 渲染、`EvalHistoryStore` 历史），`RagEvalTest` 退化为薄壳。备选：放 main 源码集并暴露 `/eval/run` 由后端跑自己——否，会把评测期 Ollama 依赖带进运行时进程，且后端进程内自测与 CI 路径重复。
2. **归档用 git 文件而非数据库**：`docs/eval/history/` 进 git，PR 中可直接 review 指标变化；免建表免迁移。备选 MySQL `eval_run` 表——查询强但前端/PR 两处消费都要适配，规模（周频运行）远未到。
3. **归档粒度为目录 + 双文件**：`summary.json`（metrics + config 快照 + counts + 时延）供程序消费，`report.md` 供人审，目录名 `<UTC日期>-<git短哈希>-<序号>` 保证同日多次不冲突。
4. **趋势 diff 用方向语义表**：`{recallAt4: ↑好, mrr: ↑好, separation: ↑好, wrongInjectRate: ↓好, injectRecall: ↑好, answerHitRate: ↑好, citationAccuracy: ↑好, faithfulnessAvg: ↑好, p50/p95: ↓好}`，±0.03 阈值外显著标记；不自动 fail（fail 仍由既有基线断言负责，趋势只供决策）。
5. **触发三档**：统一入口 = Maven 单命令（`-Dgroups=rag-eval` + 环境变量封装进 `run-eval.sh`）；pre-push（非 pre-commit：评测需 Ollama 且耗时 ~3 分钟，commit 频率下不可接受）用 `git diff --name-only` 做路径感知，Ollama 不可达时 fail-open（警告放行），CI 才 fail-closed；GitHub Actions 提供 Ollama service 容器 + `ollama pull bge-m3` + MySQL service（schema 初始化），仅检索层。
6. **候选池落 MySQL**：表 `eval_candidate`（status: pending/promoted/discarded）。pending 查重用应用层规范化比对（`LOWER(TRIM(question))` 查 pending 集合），不用 DB 唯一索引（MySQL 无部分索引，会误伤历史已 triage 记录）。
7. **转正直写标注集文件**：promote 接口在进程内对 `src/test/resources/rag-eval-cases.json` 读-改-写（Jackson 保持数组格式，写入后立即回读校验合法性），候选标记 promoted；开发者需 git commit 该文件（triage 界面提示）。备选：由后端自动 git commit——越权，否。
8. **裁判模型默认 deepseek-chat、可配置**：与被测同源存在自评偏差，通过 `rag.eval.judge-model` 环境变量可换（如换 Qwen-Max 对评）；test 进程内用 `java.net.http` 直连 DeepSeek `/chat/completions`（已有 key），不依赖 Spring agent bean。强制 JSON 输出（prompt 约束 + 解析失败重试 1 次）。
9. **前端图表选 @ant-design/charts**：与 antd 生态/主题一致；页面级懒加载避免拖累首屏。备选 recharts——更轻但风格需手动对齐黏土主题。
10. **只读 API 数据源 = 归档目录**：`EvalReportController` 读 `rag.eval.history-dir`（默认 `docs/eval/history`）下的 summary.json 列表；目录缺失返回空数组。Docker 场景将该目录挂为卷或由 CI 产物另行分发（本变更仅实现本地/NAS 形态）。

## Risks / Trade-offs

- **归档线性增长**：git 仓库随运行次数增长（每次 ~10KB），周频使用一年 ~500KB，可接受；超 200 份再做保留策略（保留首末+每月）。
- **pre-push 依赖本地 Ollama**：机器未起 Ollama 时 fail-open 会放行回归——接受（CI 兜底），钩子输出显著警告。
- **裁判自评偏差**：DeepSeek 判 DeepSeek 可能偏宽——已留模型切换口子；必要时引入双裁判取均值。
- **后端写仓库文件**：promote 需要后端对工作区有写权限，容器化部署时该接口无意义——triage 界面在检测到归档目录不可写时禁用转正按钮。
- **评测运行时长增长**：新增 judge/时延后生成层单次 ~5 分钟——已用环境变量门控，检索层（本地高频）不受影响。

## Open Questions

- CI 中 bge-m3 下载（~1.2GB）缓存策略：先用 GitHub Actions cache 目录 `~/.ollama` 直接缓存，命中率待观察，不阻塞本变更。
