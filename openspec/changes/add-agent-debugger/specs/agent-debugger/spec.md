# Spec: Agent 推理调试器

## 需求

### REQ-1: 步骤列表

**Given** 用户选择一个对话  
**When** 访问 GET /api/debug/{conversationId}/steps  
**Then** 返回该对话所有 assistant 消息的步骤列表  
**And** 每条消息按 step_index 升序排列  
**And** 包含 step_type, content, tool_name, tool_args, tool_result, duration_ms

### REQ-2: 上下文重建

**Given** 用户点击某个步骤  
**When** 访问 GET /api/debug/{conversationId}/context/{messageId}/{stepIndex}  
**Then** 返回 system_prompt + 该消息前的历史消息 + 步骤 1..N-1 的累积结果 + 当前步骤内容  
**And** 如果该步骤有断点，包含断点信息

### REQ-3: 断点管理

**Given** 用户在步骤上添加断点  
**When** POST /api/debug/breakpoints (action=add)  
**Then** 在 debug_breakpoint 表中创建记录  
**And** 重复添加同一位置返回成功（幂等）  
**And** action=remove 时删除该断点

### REQ-4: 路径 Diff

**Given** 用户选择两个对话  
**When** 访问 GET /api/debug/diff?convA=1&convB=2  
**Then** 取两个对话最后一条 assistant 消息的 trace  
**And** 按 step_index 对齐比较  
**And** 标记第一个分叉步骤（tool_name 或 tool_args 有差异）  
**And** 返回对齐表

### REQ-5: 空数据降级

**Given** 对话没有 trace 数据  
**When** 访问 steps 或 context API  
**Then** 返回空列表或空对象  
**And** 不报错

### REQ-6: 用户隔离

**Given** 用户 A 的断点  
**When** 用户 B 查询断点  
**Then** 不可见  
**And** 上下文重建校验 conversation 属于当前用户
