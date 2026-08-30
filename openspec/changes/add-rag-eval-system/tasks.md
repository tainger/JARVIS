## 1. 评测内核重构（不改行为）

- [x] 1.1 新建 `src/test/java/com/example/jarvis/eval/` 包，把 `RagEvalTest` 的加载/评分/报告逻辑拆为 `EvalRunner`（编排与指标计算）、`EvalReportRenderer`（MD 渲染）、`EvalModels`（用例与结果模型）；`RagEvalTest` 保留两个门控用例方法为薄壳。验证：`RAG_EVAL=true ./mvnw test -Dtest=RagEvalTest` 与重构前指标逐项一致（对照现归档基线 1.000/0.839/0.208/0/12/0.813）
- [x] 1.2 生成层逻辑同步拆出（答案要素、引用指向、HttpClient 调用）。验证：`RAG_EVAL_LLM=true ./mvnw test -Dtest=RagEvalTest#evalGeneration` 通过且报告内容一致

## 2. 归档与趋势（eval-history）

- [x] 2.1 新增 `EvalArchiveWriter`：运行结束写 `docs/eval/history/<UTC日期>-<git短哈希>-<序号>/{summary.json,report.md}`，summary 含指标、config 快照（权重/min-score/inject-score/分块/embedding 模型）、用例数、时间戳。验证：跑一次评测后归档目录存在、summary.json 可被 Jackson 回读且字段齐全
- [x] 2.2 新增 `EvalHistoryStore`：读取最近归档 + 目录不存在/为空返回空。验证：单测覆盖空目录与多归档场景
- [x] 2.3 报告渲染 diff 表（方向语义 map，±0.03 显著标记，首次运行标"基线首次建立"）。验证：连续跑两次评测，第二次报告出现 diff 表且方向标注正确
- [x] 2.4 把当前 `target/` 的最近一次结果迁移为首个归档（手工生成一次运行即可）。验证：`openspec` 外部——`docs/eval/history/` 含 1 份基线归档

## 3. 触发机制（eval-trigger）

- [x] 3.1 新增 `run-eval.sh`：封装环境变量与 `./mvnw test -Dgroups=rag-eval -Dtest=RagEvalTest`，支持 `--llm` 参数开启生成层。验证：`./run-eval.sh` 一键完成并产出归档
- [x] 3.2 新增 `deploy/hooks/install.sh`（安装 pre-push）与 `pre-push` 脚本本体：路径感知（rag/**、application.properties、schema.sql、标注集、eval 包），Ollama 不可达 fail-open 警告放行，评测失败拒绝 push 并打印失败指标。验证：修改 `KnowledgeService` 一行后 push 被拦截；仅改 `web/**` 后 push 直通；`--no-verify` 可逃生
- [x] 3.3 新增 `.github/workflows/eval.yml`：paths 触发（同上路径集），服务容器 MySQL（执行 schema.sql）+ Ollama（pull bge-m3，缓存 `~/.ollama`），只跑检索层，上传归档为 artifact。验证：workflow YAML 语法本地 lint（`act` 或 `yamllint`），推送后 GitHub check 绿

## 4. 候选池流水线（eval-case-pipeline）

- [x] 4.1 `schema.sql` 新增 `eval_candidate` 表（id、question、question_norm、note、expected_doc、source、chat_ref、status、created_at、triaged_at），幂等建表。验证：重启后端表自动创建
- [x] 4.2 新增 model/mapper/`EvalCandidateController`：`POST /api/knowledge/eval/candidates`（必填校验 400、pending 规范化查重 409 返回既有 id、成功 201）、`GET ?status=` 分页倒序、`POST /{id}/promote`（补全标注→追加写标注集 JSON→回读校验合法→置 promoted；重复 triage 409）、`POST /{id}/discard`。验证：curl 走通 201→409（重复）→promote→JSON 合法且用例数 +1→再 promote 得 409 全链路
- [x] 4.3 接口纳入登录鉴权（沿用 SecurityConfig 现有规则）。验证：无 token 调用返回 401

## 5. 指标深度（eval-metric-depth）

- [x] 5.1 `EvalJudge`：直连 DeepSeek `/chat/completions`（judge-model 可配置），prompt 强制输出 `{"score":1-5,"reason":"..."}`，解析失败重试 1 次；生成层逐用例打分，报告输出平均分/逐条分/覆盖率，平均分 <4.0 或覆盖率 <0.8 判失败。验证：`RAG_EVAL_LLM=true` 跑生成层，报告中出现忠实度列且断言生效（可临时注入低质片段观察低分）
- [x] 5.2 时延统计：检索层逐用例记录 search 耗时、生成层记录端到端耗时，报告输出检索/端到端 p50、p95 及分类型均值，写入 summary.json。验证：归档 summary 中可回查逐条耗时，报告四个分位数字齐全
- [x] 5.3 文档维度分层表：按 expectDoc 分组的 Recall@4/MRR/Top1 均分。验证：报告出现文档维度表且与分类型表数值自洽

## 6. 只读历史 API（eval-report-ui 后端部分）

- [x] 6.1 新增 `rag.eval.history-dir` 配置（默认 `docs/eval/history`）与 `EvalReportController`：`GET /api/knowledge/eval/history`（列表摘要）、`GET .../history/{runId}`（完整 summary + 与前一次 diff）；目录缺失返回空数组，接口登录鉴权。验证：curl 空目录得 200 空数组；有归档后得列表与单次详情
- [x] 6.2 `deploy/` 与 compose 说明补挂载卷（容器形态下归档目录持久化）。验证：docker-compose.yml 注释/文档可查

## 7. 前端评测中心（eval-report-ui）

- [x] 7.1 安装 `@ant-design/charts`，新增路由 `/eval` 与 `EvalCenter.jsx`：指标卡（达标状态）、趋势图（Recall@4/MRR/误注入率时间轴）、用例明细表（类型/结果筛选、失败用例展开 Top-K 详情）、空状态引导。验证：`npm run build` 通过；页面在有无归档两种状态下渲染正确
- [x] 7.2 `client.js` 新增 history/candidates API 封装。验证：页面网络面板请求成功
- [x] 7.3 候选池 triage 区块：待处理列表、转正表单（type/expectDoc/关键词）、丢弃，操作后刷新；归档目录不可写时禁用转正。验证：从 👎 提交一条候选并在界面完成转正，标注集文件出现新 case
- [x] 7.4 `Chat.jsx` AI 气泡 footer 新增 👎 按钮（弹窗填 note 后提交候选，带 question 与回答摘要）。验证：聊天中点 👎 后候选池出现记录

## 8. 文档与收尾

- [x] 8.1 更新 `docs/rag-design.md`：Phase1-#5 补"完整评测系统"链接与能力清单，基线章节指向归档目录。验证：文档链接可达
- [x] 8.2 更新 `README.md` 与 `docs/setup-guide.md`：评测系统使用章节（一键命令、钩子安装、候选池、评测中心）。验证：按文档可从零跑通一次评测
- [ ] 8.3 `openspec validate add-rag-eval-system` 通过后提请归档。验证：validate 输出无错误
