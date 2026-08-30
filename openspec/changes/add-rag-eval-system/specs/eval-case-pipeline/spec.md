## Purpose

让标注集跟着真实使用生长：使用中发现的 bad case（如"帮我列出所有任务"暴露的工具意图盲区）通过候选池沉淀、triage、转正入集，替代"想起来才手动补 case"的静态维护方式。

## ADDED Requirements

### Requirement: 候选提交

系统 SHALL 提供 `POST /api/knowledge/eval/candidates` 接口：请求体含 `question`（必填）、`note`（现象描述，必填）、`expectedDoc`（预期命中文档，选填）、`source`（来源：manual/chat）、`chatRef`（聊天消息引用，选填），成功创建返回 201 与候选 id。`question` 去空格后为空 MUST 返回 400。规范化后（去空格、转小写）与**待处理池中已有候选重复**的提交 MUST 返回 409 并附已有候选 id。

#### Scenario: 从聊天界面提交 bad case

- **WHEN** 用户在 AI 回答下点击 👎 并填写现象说明提交
- **THEN** 候选池新增一条 source=chat 的候选记录，返回 201

#### Scenario: 重复提交

- **WHEN** 待处理池中已存在"帮我列出所有任务"（规范化后）的候选，再次提交相同问题
- **THEN** 返回 409 与既有候选 id，不产生重复记录

### Requirement: 候选转正入集

系统 SHALL 提供 triage 接口：`POST /api/knowledge/eval/candidates/{id}/promote` 接收补全的标注信息（type、expectDoc、expectChunkKeywords，可含 expectAnswerKeywords），将候选以生成的唯一 id 追加写入 `src/test/resources/rag-eval-cases.json`，并将候选状态置为 promoted；`POST .../discard` 将候选置为 discarded（保留记录，不入集）。转正写入 MUST 保持 JSON 数组格式合法（写入后可被评测加载器解析）。已 promoted/discarded 的候选再次 triage MUST 返回 409。

#### Scenario: 工具意图候选转正

- **WHEN** 对候选"帮我列出所有任务"调用 promote，标注 type=工具意图、irrelevant=true
- **THEN** 标注集文件末尾追加该 case（id 唯一、格式合法）
- **AND** 下一次评测运行加载的用例数 +1

### Requirement: 待处理池查询

系统 SHALL 提供 `GET /api/knowledge/eval/candidates?status=pending|promoted|discarded` 分页查询（按创建时间倒序），供 triage 界面与流水线消费。

#### Scenario: 查看待处理列表

- **WHEN** 调用 status=pending 的查询
- **THEN** 仅返回未 triage 的候选，按提交时间倒序排列
