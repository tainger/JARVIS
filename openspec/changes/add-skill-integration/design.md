# Design: Add Skill Integration

## Context

### 现状分析

| 维度 | 现状 | 缺口 |
|------|------|------|
| 工具暴露 | 全部 7 个 @Tool 无差别注册到共享 Toolkit | 缺按场景过滤机制 |
| 模式切换 | `buildSystemPrompt()` 硬编码 switch(mode) 2 种模式 | 缺数据驱动的扩展能力 |
| 前端入口 | Chat.jsx（通用）+ SourceAnalysis.jsx（源码）两个独立页面 | 缺统一的技能选择器 |
| 技能概念 | 无 | 无技能抽象层 |
| 管理 API | 无 | 无技能 CRUD API |

### 关键代码位置

- `AgentScopeConfig.java` 第 35-45 行：`agentscopeToolkit()` Bean——注册全部工具
- `AgentFactory.java` 第 30-50 行：`create()` 方法——注入 sharedToolkit
- `AgentController.java` 第 60-75 行：`buildSystemPrompt()`——硬编码 switch
- `ChatRequest.java`：`record(message, conversationId, mode)`
- `Chat.jsx` 第 30-50 行：`send()` 函数——SSE 请求体构建
- `SourceAnalysis.jsx`：固定 `mode: 'source-analysis'`

### 工具清单

| 工具类 | @Tool 方法 | 适用场景 |
|--------|-----------|----------|
| `TaskTools` | listTasks / getTask / createTask | 任务管理 |
| `SourceCodeTools` | readFile / listFiles / grepCode | 源码分析 |
| `KnowledgeSearchTools` | knowledgeSearch | 知识问答 |

## Goals / Non-Goals

**Goals:**
- 技能数据驱动：系统提示词 + 工具白名单 + 场景描述存储在 DB 中，不硬编码
- 按技能过滤工具：减少 LLM 决策空间，避免误调用无关工具
- 前端技能选择器：用户在对话中切换技能，无需跳转页面
- 4 个内置技能覆盖现有场景，后续可扩展自定义技能

**Non-Goals:**
- 不做技能管理 UI（技能管理菜单在后续变更中设计）
- 不做技能 CRUD API（V1 只读，管理 API 随管理菜单一起设计）
- 不做多技能组合（V1 每次对话只激活一个技能）
- 不做技能自动推荐（V1 由用户手动选择技能）
- 不做 MCP 工具过滤（V1 MCP 全部注释禁用，未来启用时再考虑）
- 不做技能执行编排（V1 技能只影响 prompt + 工具过滤，不做预编排工作流）

## Decisions

### 决策 1：技能 = 系统提示词 + 工具白名单 + 场景描述

**选择**：每个技能是一个三元组 `(systemPrompt, toolWhitelist, description)`。

```
Skill {
  name: "source-code-analysis"           // 技能标识
  displayName: "源码分析"                // 前端显示名
  description: "分析项目源码，定位实现"     // 技能描述
  systemPrompt: "You are JARVIS..."      // 系统提示词
  toolWhitelist: "SourceCodeTools,KnowledgeSearchTools"  // 工具白名单
  icon: "CodeOutlined"                   // Ant Design 图标
  enabled: true                         // 是否启用
  isBuiltIn: true                       // 是否内置（不可删除）
}
```

**理由**：
- **系统提示词**：引导 LLM 的行为方向（面向源码 / 面向任务 / 面向知识库）
- **工具白名单**：收窄工具选择空间，源码分析时 LLM 不需看到 `listTasks`
- **场景描述**：前端选择器展示，帮助用户理解每个技能的适用场景

**备选**：技能 = 预编排工作流（固定工具调用序列）→ 否决，V1 先做最简单的 prompt + filter 层，积累使用数据后再决定哪些链路值得固化为预编排

### 决策 2：`agent_skill` 表 + Flyway V5 种子数据

**选择**：新建 `agent_skill` 表，Flyway V5 创建表并插入 4 条内置技能种子数据。

```sql
CREATE TABLE IF NOT EXISTS agent_skill (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(50)  NOT NULL UNIQUE,   -- 技能标识（英文）
  display_name    VARCHAR(100) NOT NULL,           -- 前端显示名（中文）
  description    VARCHAR(500) NOT NULL,            -- 技能描述
  system_prompt   TEXT         NOT NULL,           -- 系统提示词
  tool_whitelist  VARCHAR(500) NOT NULL DEFAULT 'all',  -- 工具白名单
  icon            VARCHAR(50)  DEFAULT 'RobotOutlined',  -- Ant Design 图标名
  enabled         TINYINT(1)   DEFAULT 1,          -- 是否启用
  is_built_in     TINYINT(1)   DEFAULT 0,          -- 是否内置
  sort_order      INT          DEFAULT 0,          -- 排序
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**内置种子数据**（4 条）：

| name | display_name | tool_whitelist | icon |
|------|-------------|----------------|------|
| general-chat | 通用对话 | all | RobotOutlined |
| source-code-analysis | 源码分析 | SourceCodeTools,KnowledgeSearchTools | CodeOutlined |
| task-management | 任务管理 | TaskTools | UnorderedListOutlined |
| knowledge-qa | 知识问答 | KnowledgeSearchTools | BookOutlined |

**理由**：
- DB 存储比硬编码 switch 灵活，后续管理菜单可直接 CRUD
- 种子数据让 V1 开箱即用，4 个技能覆盖现有全部场景
- `is_built_in` 标记内置技能，V2 管理菜单中限制删除
- `sort_order` 控制前端选择器中的展示顺序
- `tool_whitelist = "all"` 时使用共享 Toolkit，不创建新 Toolkit

**备选**：纯配置文件（application.properties）→ 否决，无法运行时管理、无 API 查询能力

### 决策 3：per-request Toolkit 按白名单过滤

**选择**：`AgentFactory.create()` 增加 `toolWhitelist` 参数：

```java
public ReActAgent create(Long userId, Long conversationId,
                         String systemPrompt, String toolWhitelist) {
    Toolkit toolkit = resolveToolkit(toolWhitelist);
    // ... 其余不变
}

private Toolkit resolveToolkit(String whitelist) {
    if (whitelist == null || "all".equalsIgnoreCase(whitelist)) {
        return sharedToolkit;  // 全量工具
    }
    // 按白名单创建新 Toolkit
    Toolkit tk = new Toolkit();
    Set<String> allowed = Set.of(whitelist.split(","));
    if (allowed.contains("TaskTools")) tk.registerTool(taskTools);
    if (allowed.contains("SourceCodeTools")) tk.registerTool(sourceCodeTools);
    if (allowed.contains("KnowledgeSearchTools")) tk.registerTool(knowledgeSearchTools);
    return tk;
}
```

**工具 Bean 注入**：AgentFactory 额外注入 3 个工具 `@Component` Bean（TaskTools、SourceCodeTools、KnowledgeSearchTools），用于按白名单注册。

**性能分析**：
- `Toolkit` 对象极轻量（仅维护工具方法注册表）
- `registerTool()` 只存储方法引用，不创建工具实例
- per-request Toolkit 创建开销 < 1ms
- 工具 Bean 本身是 Spring 单例，共享无状态

**理由**：
- 源码分析场景下 LLM 不需看到 `listTasks/getTask/createTask`，减少决策干扰
- 任务管理场景下 LLM 不需看到 `grepCode/readFile`，避免误调用文件系统
- 白名单为 `all` 时走共享 Toolkit 快路径，零额外开销

**备选**：在 system prompt 中告知 LLM "不要使用某些工具" → 否决，LLM 可能不遵守；硬过滤更可靠

### 决策 4：ChatRequest 增加 `skill` 字段，向后兼容 `mode`

**选择**：

```java
public record ChatRequest(
    String message,
    Long conversationId,
    String mode,      // 保留，向后兼容
    String skill      // 新增，优先于 mode
) {}
```

`AgentController.chatStream()` 解析逻辑：

```java
SkillResolver.ResolvedSkill skill = skillResolver.resolve(request.skill(), request.mode());
// skill.systemPrompt() → 传给 AgentFactory
// skill.toolWhitelist() → 传给 AgentFactory
```

`SkillResolver` 回退顺序：
1. `request.skill()` 非空 → 按 name 查 `agent_skill` 表
2. `request.mode()` 非空 → 映射到对应技能名（`source-analysis` → `source-code-analysis`）
3. 都为空 → 使用默认技能 `general-chat`

**理由**：
- `skill` 优先于 `mode`，新前端用 `skill`，旧前端（SourceAnalysis.jsx）仍用 `mode` 不受影响
- `mode → skill` 映射让现有 `source-analysis` 模式平滑迁移
- 默认技能 `general-chat` 保证无 skill 参数时行为不变

**备选**：直接废弃 `mode` → 否决，破坏 SourceAnalysis.jsx 和现有 API 契约

### 决策 5：SkillResolver 解析服务

**选择**：新增 `SkillResolver` 服务（`@Component`），提供 `resolve(skillName, mode)` 方法返回 `ResolvedSkill`。

```java
@Component
public class SkillResolver {
    private final AgentSkillMapper skillMapper;
    
    public record ResolvedSkill(String name, String systemPrompt, String toolWhitelist) {}
    
    public ResolvedSkill resolve(String skillName, String mode) {
        // 1. 优先按 skillName 查
        if (StringUtils.hasText(skillName)) {
            AgentSkill skill = skillMapper.findByName(skillName);
            if (skill != null && skill.getEnabled()) {
                return new ResolvedSkill(skill.getName(), 
                    skill.getSystemPrompt(), skill.getToolWhitelist());
            }
        }
        // 2. 回退到 mode 映射
        if (StringUtils.hasText(mode)) {
            String mapped = mapModeToSkill(mode);
            AgentSkill skill = skillMapper.findByName(mapped);
            if (skill != null && skill.getEnabled()) {
                return new ResolvedSkill(skill.getName(),
                    skill.getSystemPrompt(), skill.getToolWhitelist());
            }
        }
        // 3. 默认技能
        AgentSkill def = skillMapper.findByName("general-chat");
        return new ResolvedSkill(def.getName(), def.getSystemPrompt(), def.getToolWhitelist());
    }
    
    private String mapModeToSkill(String mode) {
        return switch (mode) {
            case "source-analysis" -> "source-code-analysis";
            default -> "general-chat";
        };
    }
}
```

**理由**：
- 解析逻辑集中在一处，AgentController 不需关心回退顺序
- `ResolvedSkill` 是不可变值对象，线程安全
- 后续管理菜单设计时可复用 `SkillResolver` 验证技能配置

### 决策 6：只读 API `GET /api/skills`

**选择**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /api/skills` | 列出所有启用技能 | 按 sort_order 排序 |
| `GET /api/skills/{id}` | 查看技能详情 | 含完整 systemPrompt |

**响应格式**：
```json
[
  {
    "id": 1,
    "name": "general-chat",
    "displayName": "通用对话",
    "description": "通用智能助手，支持任务管理、知识问答和源码分析",
    "icon": "RobotOutlined",
    "isBuiltIn": true,
    "sortOrder": 0
  },
  ...
]
```

**理由**：
- 前端选择器只需 name + displayName + icon + description
- 详情 API 返回完整 systemPrompt（V2 管理菜单编辑时需要）
- V1 不返回 toolWhitelist（前端不需知道工具细节）
- 沿用 `/api/**` JWT 鉴权

**备选**：无详情 API → 否决，V2 管理菜单需要；前端硬编码技能列表 → 否决，失去数据驱动优势

### 决策 7：前端技能选择器

**选择**：在 Chat.jsx 的输入区上方新增技能下拉选择器。

```
┌──────────────────────────────────────────┐
│ 🤖 JARVIS AI 助手  [技能: 通用对话 ▼]     │
├──────────────────────────────────────────┤
│ (消息列表)                                │
├──────────────────────────────────────────┤
│ [TextArea 输入消息...]            [发送] │
└──────────────────────────────────────────┘
```

- `Select` 组件，`GET /api/skills` 加载选项
- 选项格式：图标 + displayName + description
- 默认选中 `general-chat`
- 选中的技能名随 `streamChat()` 请求发送
- 切换技能时清空输入框但不影响已有消息

**SourceAnalysis.jsx**：内部固定 `skill: "source-code-analysis"`，不显示选择器（保持现有行为）。

**理由**：
- 统一在 Chat.jsx 中通过选择器切换场景，无需多个独立页面
- SourceAnalysis.jsx 作为专用入口保持简单（固定技能）
- 后续管理菜单是另一个独立页面，不影响对话流程

## Risk Mitigation

- **[技能解析失败]** DB 查不到技能名 → 缓解：三级回退（skill → mode → 默认技能），保证总有提示词
- **[Toolkit 创建开销]** per-request Toolkit 创建 → 缓解：`all` 白名单走共享快路径，过滤场景创建 < 1ms
- **[MCP 工具未过滤]** per-request Toolkit 不注册 MCP → 缓解：V1 MCP 全部注释禁用，无实际影响；V2 启用 MCP 时评估是否需要过滤
- **[内置技能被误删]** V1 无删除 API → 缓解：只读 API 不暴露删除入口；`is_built_in` 标记为 V2 管理菜单预留
- **[system_prompt 过长]** DB TEXT 字段存储完整提示词 → 缓解：TEXT 最大 64KB，远超提示词长度需求
- **[向后兼容]** 旧前端只传 `mode` → 缓解：`mode → skill` 映射确保旧行为不变

## Migration Plan

1. Flyway V5 创建 `agent_skill` 表 + 4 条种子数据
2. 新增 `AgentSkill` 实体 + `AgentSkillMapper` + XML
3. 新增 `SkillResolver` 解析服务
4. 新增 `SkillController` 只读 API
5. 修改 `AgentFactory`：注入工具 Bean + `resolveToolkit()` 方法
6. 修改 `ChatRequest`：新增 `skill` 字段
7. 修改 `AgentController`：调用 `SkillResolver` + 传 `toolWhitelist` 给 AgentFactory
8. 修改前端 `client.js`：新增 `skillApi.list()` + `streamChat()` 传 skill
9. 修改 `Chat.jsx`：新增技能选择器
10. 修改 `SourceAnalysis.jsx`：内部使用 `skill: "source-code-analysis"`
11. 重启后端 + 前端，验证技能选择器可见、工具过滤生效
