## Purpose

提供基于角色的精细化权限控制体系，覆盖菜单可见性、操作权限（接口/按钮）和数据权限（知识库内容访问）三个层级，支持多用户场景下的权限隔离与管理。

## ADDED Requirements

### Requirement: 角色与权限模型

系统 SHALL 提供基于角色的访问控制（RBAC），用户通过角色获得权限，一个用户可拥有多个角色，一个角色可包含多个权限。

- 权限分为三类：`menu`（菜单权限）、`action`（操作权限）、`data`（数据权限）
- 权限编码格式：`<模块>:<资源>:<操作>`，如 `knowledge:document:create`
- 角色与权限为多对多关系，用户与角色为多对多关系
- 内置两个默认角色：`admin`（全部权限）、`user`（基础对话 + 公开知识库权限）

#### Scenario: 默认角色自动分配
- **WHEN** 新用户注册成功
- **THEN** 系统自动为该用户分配 `user` 角色

#### Scenario: 管理员拥有全部权限
- **WHEN** 管理员（admin 角色）访问任意菜单或接口
- **THEN** 所有权限校验通过

### Requirement: 菜单权限控制

前端侧边栏菜单 SHALL 根据当前用户的菜单权限动态渲染，无权限的菜单不显示。

- 菜单权限编码格式：`menu:<菜单key>`，如 `menu:knowledge`、`menu:debugger`、`menu:user-profile`
- 后端登录接口返回用户信息时，一并返回该用户拥有的所有权限列表
- 前端路由守卫 SHALL 校验菜单权限，无权限访问路径时跳转 403 页面

#### Scenario: 无菜单权限的用户看不到对应菜单
- **WHEN** 普通用户（只有 user 角色）登录系统
- **THEN** 侧边栏不显示"推理调试"、"用户管理"等无权限菜单

#### Scenario: 直接输入无权限路径
- **WHEN** 用户手动在浏览器地址栏输入无权限的页面路径
- **THEN** 前端路由守卫拦截，跳转到 403 无权限页面

### Requirement: 操作权限控制（接口级）

后端所有受保护接口 SHALL 进行权限校验，用户必须拥有对应操作权限才能调用。

- 使用注解方式声明接口所需权限：`@PreAuthorize("hasPermission('knowledge:document:create')")`
- 无权限时返回 403 状态码和错误信息
- 前端根据权限列表控制按钮显示/禁用，无权限的按钮不渲染或置灰

#### Scenario: 无操作权限调用接口
- **WHEN** 普通用户调用 `DELETE /api/knowledge/documents/{id}`（无删除权限）
- **THEN** 后端返回 403 Forbidden，文档不被删除

#### Scenario: 无权限按钮不显示
- **WHEN** 普通用户访问知识库页面
- **THEN** "删除文档"按钮不渲染（或置灰不可点击）

### Requirement: 数据权限 — 知识库访问控制

知识库文档 SHALL 支持设置访问范围，检索时自动过滤用户无权访问的文档。

- 访问范围分为三档：`public`（公开，所有登录用户可见）、`private`（仅创建者可见）、`role_based`（指定角色可见）
- 知识库文档表增加 `access_level` 和 `owner_id` 字段
- `role_based` 模式通过中间表关联角色与文档
- 检索时（`knowledgeSearch` 工具和知识库页面）自动注入数据权限过滤条件
- 管理员（admin 角色）不受数据权限限制，可查看所有文档

#### Scenario: 公开文档所有用户可检索
- **WHEN** 文档 access_level = public
- **THEN** 所有登录用户在检索结果中都能看到该文档

#### Scenario: 私有文档仅创建者可见
- **WHEN** 文档 access_level = private，owner_id = 1
- **THEN** 只有 user_id = 1 的用户能检索到该文档，其他用户检索结果中不包含

#### Scenario: 角色级文档仅指定角色可见
- **WHEN** 文档 access_level = role_based，关联了 "manager" 角色
- **THEN** 只有拥有 manager 角色的用户能检索到该文档

#### Scenario: 管理员不受数据权限限制
- **WHEN** admin 角色用户进行知识库检索
- **THEN** 返回所有文档的检索结果，不受 access_level 限制

### Requirement: 角色管理

系统 SHALL 提供角色管理功能，管理员可创建、编辑、删除自定义角色，并配置角色的菜单权限、操作权限和数据权限范围。

- 角色列表：查看所有角色及其权限统计
- 角色编辑：勾选菜单权限、操作权限，设置数据权限范围
- 内置角色（admin、user）不可删除，但可修改权限（admin 除外）
- 删除角色时，拥有该角色的用户自动失去对应权限

#### Scenario: 管理员创建新角色
- **WHEN** 管理员在角色管理页面创建"content-editor"角色，勾选知识库的查看和编辑权限
- **THEN** 角色创建成功，拥有该角色的用户获得对应权限

#### Scenario: 删除角色后用户失去权限
- **WHEN** 管理员删除"content-editor"角色
- **THEN** 拥有该角色的用户在下一次请求时失去对应的权限

### Requirement: 用户角色分配

管理员 SHALL 为用户分配或撤销角色。

- 用户详情页可查看和修改用户的角色列表
- 一个用户可拥有多个角色，权限取并集
- 修改用户角色后，用户的权限在下一次 token 刷新时生效

#### Scenario: 为用户分配多个角色
- **WHEN** 管理员为用户 A 同时分配 "user" 和 "content-editor" 两个角色
- **THEN** 用户 A 拥有两个角色权限的并集

### Requirement: 权限缓存与刷新

用户权限 SHALL 在登录时加载并缓存，角色变更后有机制通知重新加载。

- 登录成功后，用户权限列表存入 JWT token 或用户上下文
- 管理员修改角色权限后，受影响用户的下一次请求 SHALL 重新加载权限
- 权限校验优先从缓存读取，减少数据库查询

#### Scenario: 角色变更后权限实时失效
- **WHEN** 管理员移除了某个角色的知识库删除权限
- **THEN** 拥有该角色的用户在下一次调用删除接口时返回 403

### Requirement: 向后兼容

权限系统 SHALL 对现有功能保持向后兼容，升级后不影响已有的用户体验。

- 已有用户自动分配 `user` 角色
- 新建知识库文档默认为 `public` 访问范围
- 管理员（admin 角色）拥有全部权限，不受任何限制
- 未显式配置权限的接口默认放行（渐进式接入，不一次性锁死所有功能）

#### Scenario: 升级后普通用户仍可正常对话
- **WHEN** 系统升级后，已有普通用户登录
- **THEN** 用户可正常使用对话功能和访问公开知识库，体验与升级前一致

#### Scenario: 未配置权限的接口默认放行
- **WHEN** 调用尚未添加权限注解的接口
- **THEN** 接口正常响应，不做权限拦截

### Requirement: Agent Skill 使用权限

Agent 的技能（Skill）使用 SHALL 受权限控制，用户只能使用其角色授权范围内的技能。

- Skill 权限编码格式：`agent:skill:<skill-name>:use`，如 `agent:skill:source-code-analysis:use`
- 内置四个技能的权限：`general-chat`、`source-code-analysis`、`task-management`、`knowledge-qa`
- 后端创建 Agent 时（SkillResolver 解析 skill 后），根据用户权限过滤 Toolkit 中的工具，无权限的工具不注册
- 前端对话页面的技能选择器只显示用户有权限的 skill
- 用户无权使用某个 skill 时，请求创建该 skill 的对话应返回 403

#### Scenario: 无权限的技能不出现在选择器中
- **WHEN** 普通用户（只有 general-chat 权限）打开对话页面
- **THEN** 技能选择器中只显示 "通用对话"，不显示 "代码分析"、"任务管理" 等无权限技能

#### Scenario: 后端过滤无权限工具
- **WHEN** 普通用户请求使用 source-code-analysis 技能创建对话
- **THEN** 后端校验权限，用户无 `agent:skill:source-code-analysis:use` 权限，返回 403

#### Scenario: 管理员拥有所有技能权限
- **WHEN** admin 角色用户使用 Agent
- **THEN** 所有技能和工具均可用，不受限制

#### Scenario: user 角色默认拥有基础技能
- **WHEN** 新用户注册或老用户升级后，自动分配 user 角色
- **THEN** 用户拥有 `agent:skill:general-chat:use` 和 `agent:skill:knowledge-qa:use` 权限，可使用通用对话和知识库问答技能

### Requirement: Skill 级工具白名单过滤

Agent 在执行过程中调用的工具 SHALL 受用户权限约束，即使通过 Prompt 注入也无法调用无权限的工具。

- 工具权限编码格式：`agent:tool:<tool-name>:use`，如 `agent:tool:listFiles:use`
- 更细粒度的控制：同一 skill 内可限制某些工具的使用（如允许 knowledgeSearch 但不允许 createTask）
- AgentFactory 创建 Toolkit 时，根据用户权限过滤工具白名单
- 工具执行前二次校验权限，防止通过 Prompt 注入绕过

#### Scenario: 工具级权限过滤
- **WHEN** 用户拥有 source-code-analysis 技能权限，但被限制了 writeFile 工具权限
- **THEN** Agent 可以调用 listFiles、readFile，但调用 writeFile 时被拦截并返回权限错误

#### Scenario: Prompt 注入无法绕过权限
- **WHEN** 用户在对话中尝试让 Agent 调用无权限的工具（通过 Prompt 注入）
- **THEN** 工具执行前的权限校验拦截该调用，Agent 收到权限不足的错误信息
