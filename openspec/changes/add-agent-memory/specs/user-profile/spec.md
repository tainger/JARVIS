# Spec: 用户画像

## 需求

### REQ-1: 画像存储

**Given** 用户首次对话  
**When** 画像提取被触发  
**Then** 在 user_profile 表中创建该用户的画像记录  
**And** 记录包含 tech_stack, frequent_topics, answer_style, project_context, raw_summary  
**And** conversation_count 递增

### REQ-2: 画像提取

**Given** 用户的 conversation_count 达到 5 的倍数  
**When** 对话完成  
**Then** 异步调用 LLM，输入最近 10 条对话  
**And** LLM 返回结构化 JSON 画像  
**And** 更新 user_profile 表（不覆盖手动编辑的字段）  
**And** 提取失败不影响对话

### REQ-3: System Prompt 注入

**Given** 用户有 user_profile 记录  
**When** AgentFactory.create() 构建 Agent  
**Then** 查询用户画像  
**And** 如果画像有内容，追加到 system prompt 末尾  
**And** 画像为空时不追加

### REQ-4: 画像查看

**Given** 用户登录  
**When** 访问 /profile 页面  
**Then** 显示当前用户的画像信息  
**And** 每个字段可编辑  
**And** 显示对话次数和最近更新时间

### REQ-5: 画像手动编辑

**Given** 用户在画像页面修改字段  
**When** 点击"保存修改"  
**Then** 调用 PUT /api/profile  
**And** 更新 user_profile 表  
**And** 显示保存成功提示

### REQ-6: 手动触发提取

**Given** 用户点击"重新提取"按钮  
**When** 调用 POST /api/profile/extract  
**Then** 同步执行画像提取  
**And** 返回提取结果  
**And** 更新页面显示

### REQ-7: 空画像降级

**Given** 新用户无画像记录  
**When** AgentFactory.create() 查询画像  
**Then** 返回 null，使用默认 system prompt  
**And** 不报错
