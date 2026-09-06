# Spec: 知识库健康度监控

## 需求

### REQ-1: 僵尸文档检测

**Given** agent_trace 表中有 knowledgeSearch 调用记录  
**When** 页面请求僵尸文档列表  
**Then** 返回所有文档中被引用次数为 0 的文档列表  
**And** 每条记录包含：文档 ID、标题、片段数、导入时间  
**And** 返回 totalDocuments（总文档数）和 zombieCount（僵尸文档数）

### REQ-2: 知识盲区检测

**Given** agent_trace 表中有 knowledgeSearch 的 tool_call 记录  
**When** 对应的 tool_result 包含"检索到 0 条"  
**Then** 提取该 tool_call 的 tool_args 作为盲区查询词  
**And** 汇总相同查询词的出现次数  
**And** 返回每条记录：查询词、出现次数（traceCount）、最近出现时间（lastSeen）

### REQ-3: 文档引用热度排行

**Given** agent_trace 表中有 knowledgeSearch 的 tool_result  
**When** 从 tool_result 中正则提取"来源：标题"  
**Then** 与 knowledge_document 表的 title 匹配  
**And** 按文档分组统计引用次数  
**And** 左关联 eval_candidate 检查该文档所在会话是否有 👎 反馈  
**And** 返回每条记录：文档 ID、标题、引用次数（referenceCount）、片段数、是否有 dislike（hasDislike）、dislike 次数

### REQ-4: 前端页面展示

**Given** admin 用户打开知识健康页面  
**When** 页面加载  
**Then** 显示 3 个统计卡片（总文档数、僵尸文档数、盲区关键词数）  
**And** 显示文档引用热度柱状图（引用次数降序，有 dislike 的柱用珊瑚色）  
**And** 显示僵尸文档表格（可勾选批量删除）  
**And** 显示知识盲区表格（每行有"添加文档"按钮）

### REQ-5: 僵尸文档批量删除

**Given** 用户在僵尸文档表格中勾选多个文档  
**When** 点击"批量删除"按钮  
**Then** 弹出确认对话框  
**And** 确认后调用 `DELETE /api/knowledge/documents/{id}` 逐个删除  
**And** 删除完成后刷新僵尸文档列表和统计卡片

### REQ-6: 盲区关键词转文档

**Given** 知识盲区表格中某行  
**When** 点击"添加文档"按钮  
**Then** 跳转到知识库页面 `/knowledge`  
**And** 自动打开导入弹窗  
**And** 预填标题为该盲区关键词

### REQ-7: 数据为空时的降级

**Given** 系统无 knowledgeSearch 调用记录（全新部署）  
**When** 打开知识健康页面  
**Then** 统计卡片显示"总文档: N / 僵尸: N / 盲区: 0"  
**And** 热度排行显示空状态"暂无引用记录，开始对话后 Agent 的知识检索会生成引用数据"  
**And** 不报错
