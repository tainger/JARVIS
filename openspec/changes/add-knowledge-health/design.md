# Design: 知识库健康度监控

## 1. 数据提取策略

### 1.1 文档引用追踪（僵尸文档 + 热度排行）

`agent_trace` 表中 `tool_name='knowledgeSearch'` 的 `tool_result` 字段存储了格式化文本：

```
知识库检索到 3 条相关内容：

[1] 来源：Nacos架构指南（片段 1，相似度 0.856）
内容片段...

[2] 来源：Distro协议分析（片段 2，相似度 0.723）
内容片段...
```

**提取方案**：用正则 `来源：(.+?)（片段` 从 `tool_result` 中提取文档标题，再与 `knowledge_document.title` 匹配获取 `documentId`。

```
agent_trace (tool_name='knowledgeSearch')
  → 正则提取 tool_result 中的文档标题
  → JOIN knowledge_document ON title 匹配
  → GROUP BY document_id 统计引用次数
```

**零引用文档** = `knowledge_document` 中 `LEFT JOIN` 引用统计结果后 `count = 0` 的文档。

### 1.2 知识盲区检测

`agent_trace` 中 `tool_name='knowledgeSearch'` 且 `step_type='tool_call'` 的 `tool_args` 字段存储了搜索查询词。同会话中对应的 `tool_result` 若包含 "0 条相关内容"，则该查询词为盲区关键词。

```
agent_trace (tool_name='knowledgeSearch', step_type='tool_call')
  → 提取 tool_args 作为查询词
  → 关联同 message_id 的 tool_result，检查是否包含 "0 条"
  → 汇总盲区关键词列表
```

### 1.3 低质文档识别

关联 `eval_candidate`（👎 反馈）与 `agent_trace`：如果一个会话有 dislike 反馈，且该会话的 knowledgeSearch 引用了某些文档，则这些文档标记为"疑似低质"。

## 2. API 设计

### 2.1 GET /api/knowledge/health/zombie-docs

僵尸文档列表：被引用 0 次的文档。

```json
{
  "items": [
    { "id": 5, "title": "旧版API文档", "chunkCount": 8, "createdAt": "2026-08-01T..." }
  ],
  "totalDocuments": 12,
  "zombieCount": 3
}
```

### 2.2 GET /api/knowledge/health/blind-spots

知识盲区关键词：搜索返回 0 条结果的查询词。

```json
{
  "items": [
    { "query": "Sentinel 限流原理", "traceCount": 2, "lastSeen": "2026-09-05T..." },
    { "query": "Seata 分布式事务", "traceCount": 1, "lastSeen": "2026-09-04T..." }
  ],
  "totalBlindSpots": 5
}
```

### 2.3 GET /api/knowledge/health/doc-heat

文档引用热度排行：每个文档被引用的次数 + 是否伴随 dislike。

```json
{
  "items": [
    {
      "id": 1, "title": "Nacos架构指南", "referenceCount": 15,
      "chunkCount": 12, "hasDislike": false
    },
    {
      "id": 3, "title": "Distro协议分析", "referenceCount": 8,
      "chunkCount": 5, "hasDislike": true, "dislikeCount": 1
    }
  ]
}
```

## 3. 正则解析设计

### 3.1 文档标题提取

```java
private static final Pattern DOC_TITLE_PATTERN =
    Pattern.compile("来源：(.+?)（片段\\s+\\d+");

// 从 tool_result 提取所有引用的文档标题
Set<String> extractTitles(String toolResult) {
    Set<String> titles = new HashSet<>();
    Matcher m = DOC_TITLE_PATTERN.matcher(toolResult);
    while (m.find()) {
        titles.add(m.group(1).trim());
    }
    return titles;
}
```

### 3.2 零结果检测

```java
// tool_result 包含 "检索到 0 条" → 盲区
boolean isZeroResult(String toolResult) {
    return toolResult != null && toolResult.contains("检索到 0 条");
}
```

### 3.3 查询词提取

`tool_args` 的格式取决于 AgentScope 的序列化。当前可能是 `{"query":"xxx"}` JSON 或纯文本。需要兼容两种格式：

```java
String extractQuery(String toolArgs) {
    if (toolArgs == null || toolArgs.isBlank()) return "";
    // 尝试 JSON 解析
    if (toolArgs.startsWith("{")) {
        try {
            var node = objectMapper.readTree(toolArgs);
            return node.path("query").asText("");
        } catch (Exception e) { /* fallthrough */ }
    }
    // 纯文本兜底
    return toolArgs;
}
```

## 4. 前端设计

### 4.1 页面布局

```
┌──────────────────────────────────────────┐
│  知识库健康度                              │
├───────────────┬───────────────┬──────────┤
│  [总文档]     │  [僵尸文档]    │  [盲区词] │  ← 3 个统计卡片
├───────────────┴───────────────┴──────────┤
│  文档引用热度排行（水平柱状图 + 表格）       │  ← 全宽
├──────────────────────────────────────────┤
│  僵尸文档        │  知识盲区关键词           │  ← 左右布局
│  (表格)          │  (表格 + 添加建议)       │
└──────────────────────────────────────────┘
```

### 4.2 可视化

- **统计卡片**：3 色染色卡（总文档=紫、僵尸=红、盲区=黄），风格同 Analytics 页面
- **文档热度排行**：水平柱状图（@ant-design/charts Bar），柱旁标注引用次数，伴随 dislike 的柱用珊瑚色
- **僵尸文档表格**：Ant Design Table，列=标题/片段数/导入时间/操作（删除），支持排序
- **盲区关键词表格**：列=查询词/出现次数/最近出现时间/操作（添加为知识库文档）

### 4.3 交互

- 僵尸文档行可勾选 → 批量删除按钮
- 盲区关键词行有"添加文档"按钮 → 跳转知识库导入页面并预填标题
- 热度排行柱状图点击 → 跳转知识库文档详情

## 5. 关键决策

### 决策 1：V1 用正则解析 vs 修改 KnowledgeSearchTools 返回结构化数据

**V1 选择正则解析**。原因：
- 不改 `KnowledgeSearchTools` 的返回格式（Agent 依赖纯文本格式做推理）
- 不改 `agent_trace` 表结构（不需要新字段）
- 正则 `来源：(.+?)（片段` 对当前格式足够准确
- V2 可考虑在 trace 中增加 `tool_metadata` JSON 字段存储结构化引用信息

### 决策 2：低质文档如何定义

**V1 用 eval_candidate 关联**。如果一个文档被引用的会话中有 👎 反馈，标记 `hasDislike=true`。这不是精确的因果（dislike 可能因为其他原因），但提供了运营线索。

### 决策 3：盲区关键词的 tool_args 格式

AgentScope 的 `ToolUseBlock.getInput()` 在 SSE 流中返回空 Map（已知问题）。`tool_args` 可能存的是空 `{}`。需要从 `tool_result` 中的查询信息反推，或在 KnowledgeSearchTools 返回中包含查询词。

**实际方案**：在 `tool_result` 文本中搜索 "检索到 N 条" 获取结果数，搜索 `来源：` 获取引用文档。查询词从 `tool_args` 提取（JSON 或纯文本），如果为空则标注"未知查询"。
