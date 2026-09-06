# Design: Agent 记忆与个性化引擎

## 1. 数据模型

### 1.1 user_profile 表

```sql
CREATE TABLE user_profile (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
    tech_stack      VARCHAR(500)  COMMENT '技术栈偏好，如 Java/Spring/React',
    frequent_topics VARCHAR(1000) COMMENT '常问主题，如 Nacos架构/任务管理',
    answer_style    VARCHAR(500)  COMMENT '回答风格偏好，如 简洁代码示例/详细解释',
    project_context VARCHAR(1000) COMMENT '项目上下文，如 正在分析Nacos源码',
    raw_summary     TEXT          COMMENT 'LLM 生成的完整画像摘要',
    conversation_count INT        DEFAULT 0 COMMENT '对话次数计数（用于触发提取）',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);
```

### 1.2 与现有 user_memory 表的区别

| 维度 | user_memory (现有) | user_profile (新增) |
|------|-------------------|-------------------|
| 粒度 | 单条事实（"用户叫张三"） | 结构化画像（技术栈+主题+风格） |
| 存储 | 向量 + 文本 | 纯文本字段 |
| 用途 | 语义检索召回 | 直接注入 system prompt |
| 更新 | 每次对话 | 每 5 次对话 |

## 2. 画像提取流程

### 2.1 提取时机

在 `AgentController` 的 `onComplete` 回调中，对话完成后：
1. 递增 `user_profile.conversation_count`
2. 如果 `conversation_count % 5 == 0`，异步触发画像提取
3. 画像提取调用 LLM，输入最近 10 条对话（用户消息+助手回复），输出结构化画像 JSON

### 2.2 LLM 提取 Prompt

```
你是一个用户画像分析专家。根据以下对话历史，提取用户的画像信息。
请以 JSON 格式返回，包含以下字段：
- tech_stack: 用户的技术栈偏好（如 Java, Spring Boot, React, Python）
- frequent_topics: 用户常问的主题（如 Nacos架构, 分布式事务, 任务管理）
- answer_style: 用户偏好的回答风格（如 简洁代码示例, 详细原理解释, 图文并茂）
- project_context: 用户的项目上下文（如 正在分析Nacos 2.x源码, 开发运维工具）
- summary: 一句话总结用户画像

对话历史：
{recentConversations}

请只返回 JSON，不要其他文字。
```

### 2.3 异步执行

画像提取在独立线程中执行，不影响对话响应：
```java
CompletableFuture.runAsync(() -> {
    try {
        profileExtractionService.extractAndUpdate(userId);
    } catch (Exception e) {
        log.warn("画像提取失败: userId={}, error={}", userId, e.getMessage());
    }
});
```

## 3. System Prompt 注入

### 3.1 注入逻辑

`AgentFactory.create()` 在构建 Agent 时，查询 `user_profile` 表，如果存在画像，追加到 system prompt 末尾：

```
{原始 system prompt}

【用户画像】
技术栈：Java, Spring Boot, MyBatis
常问主题：Nacos架构, 分布式事务, 知识库管理
回答风格：简洁代码示例，附原理解释
项目上下文：正在分析 Nacos 2.x 源码

请根据以上用户画像调整回答的深度和风格。
```

### 3.2 buildSystemPrompt 增强

```java
private String buildSystemPrompt(String mode, Long userId) {
    String base = ...;  // 现有逻辑
    UserProfile profile = userProfileService.getProfile(userId);
    if (profile != null && profile.hasContent()) {
        return base + "\n\n" + profile.toPromptBlock();
    }
    return base;
}
```

## 4. API 设计

### 4.1 GET /api/profile

获取当前登录用户的画像。

```json
{
  "userId": 2,
  "techStack": "Java, Spring Boot, MyBatis",
  "frequentTopics": "Nacos架构, 分布式事务",
  "answerStyle": "简洁代码示例",
  "projectContext": "正在分析 Nacos 2.x 源码",
  "rawSummary": "Java 后端工程师，偏好...",
  "conversationCount": 15,
  "updatedAt": "2026-09-06T16:00:00"
}
```

### 4.2 PUT /api/profile

手动编辑用户画像（覆盖 LLM 提取结果）。

```json
{
  "techStack": "Java, Spring Boot",
  "frequentTopics": "Nacos, 微服务",
  "answerStyle": "简洁",
  "projectContext": "分析 Nacos 源码"
}
```

### 4.3 POST /api/profile/extract

手动触发画像提取（admin 调试用）。

## 5. 前端设计

### 5.1 页面布局

```
┌──────────────────────────────────┐
│  📋 个人画像                       │
├──────────────────────────────────┤
│  [对话次数: 15] [更新时间: ...]    │  ← 统计栏
├──────────────────────────────────┤
│  技术栈     │  [可编辑文本框]      │
│  常问主题   │  [可编辑文本框]      │  ← 表单
│  回答风格   │  [可编辑文本框]      │
│  项目上下文 │  [可编辑文本框]      │
├──────────────────────────────────┤
│  完整摘要   │  [只读展示]          │
├──────────────────────────────────┤
│  [重新提取] [保存修改]            │  ← 操作按钮
└──────────────────────────────────┘
```

## 6. 关键决策

### 决策 1：每 5 次对话提取 vs 每次对话提取

**选择每 5 次**。原因：
- 减少 LLM 调用成本（85% 降低）
- 单次对话信息不足以更新画像
- 5 次对话积累足够变化信号

### 决策 2：画像字段结构化 vs 自由文本

**选择结构化字段**。原因：
- 注入 system prompt 时格式可控
- 前端展示和编辑更友好
- LLM 提取时输出格式约束清晰

### 决策 3：手动编辑覆盖 LLM 提取

**支持手动编辑**。用户最了解自己，手动编辑的画像优先级高于 LLM 提取。LLM 提取只在手动编辑为空时填充，或在对话次数达到阈值时更新（但不覆盖手动编辑的字段）。
