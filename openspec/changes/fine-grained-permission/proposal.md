## Why

当前 JARVIS 系统的权限控制是粗粒度的：只有"登录/未登录"之分，登录用户可以访问所有菜单、所有功能、所有知识库内容。随着系统功能增多（知识库管理、推理调试、用户画像、评测中心等）和多用户场景的出现，需要一套精细化权限体系，实现四个层级的控制：**菜单可见性**（用户能看到哪些菜单）、**操作权限**（用户能点哪些按钮/调哪些接口）、**数据权限**（用户能看哪些知识库内容）、**Agent Skill 权限**（用户能使用哪些 AI 技能/工具）。

Agent Skill 级权限尤为关键：不同技能调用不同的工具集合（如 sourceCodeTools 可读写代码、taskTools 可管理任务、webSearch 可访问外网），如果不对技能使用做权限控制，普通用户可能通过 Agent 调用超出其权限范围的工具，造成安全风险。

## What Changes

- 引入 **RBAC（基于角色的访问控制）** 模型：用户 → 角色 → 权限 的三级映射
- **菜单权限**：前端根据用户权限动态渲染侧边栏菜单，无权限的菜单不显示
- **按钮/操作权限**：前端按钮级权限指令，后端接口级权限校验，双重防护
- **数据权限**：知识库支持按"公开/私有/指定角色"设置访问范围，检索时自动过滤无权限内容
- **Agent Skill 权限**：AI 技能（Skill）的使用受权限控制，无权限的技能不出现在前端选择器中，后端创建 Agent 时也会过滤无权限的工具
- 角色管理：内置 admin / user 两个默认角色，支持自定义角色
- 权限管理界面：管理员可配置角色的菜单权限、操作权限、数据权限和 Agent Skill 权限

## Capabilities

### New Capabilities

- `auth/rbac`：基于角色的精细化权限控制，涵盖菜单权限、操作权限（接口/按钮）、数据权限（知识库内容）三个层级

### Modified Capabilities

- （无）— 当前主规格目录为空，本次为全新能力

## Impact

- 新增表：`sys_role`、`sys_permission`、`sys_role_permission`、`sys_user_role`、`knowledge_document_access`（Flyway V11）
- `sys_user` 表增加角色关联（通过中间表，不直接改 user 表）
- 后端：新增 `PermissionService`、`RoleService`，Spring Security 过滤器链增加权限校验
- 后端：知识库检索增加数据权限过滤
- 后端：Agent 创建时（SkillResolver / AgentFactory）增加 Skill 权限过滤，无权限的工具不注册到 Toolkit
- 前端：新增权限 store、菜单动态渲染、按钮权限指令（`v-permission`）
- 前端：新增角色管理页面和权限配置界面（含 Agent Skill 权限配置）
- 前端：对话页面技能选择器只显示用户有权限的 skill
- 向后兼容：默认 admin 角色拥有全部权限，user 角色拥有基础对话和公开知识库权限和 general-chat 技能，老用户自动分配 user 角色
