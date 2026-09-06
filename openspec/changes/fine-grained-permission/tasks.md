# Tasks: 精细化权限控制（RBAC）

## 1. 数据库 — V11 迁移

- [ ] 1.1 创建 `V11__rbac_permission.sql`：创建 `sys_role`、`sys_permission`、`sys_role_permission`、`sys_user_role` 表
- [ ] 1.2 新增 `knowledge_document.access_level`（public/private/role_based）和 `owner_id` 字段
- [ ] 1.3 创建 `knowledge_document_role` 中间表（role_based 模式用）
- [ ] 1.4 插入默认角色（admin / user）和初始权限数据
- [ ] 1.5 已有用户默认分配 user 角色，已有文档默认为 public
- [ ] 1.6 验证 Flyway 执行迁移成功

## 2. 后端 — 模型 + Mapper

- [ ] 2.1 新建 `SysRole` / `SysPermission` / `SysRolePermission` / `SysUserRole` 实体类
- [ ] 2.2 新建 `RoleMapper` / `PermissionMapper` / `UserRoleMapper` + XML
- [ ] 2.3 `KnowledgeDocument` 增加 `accessLevel` / `ownerId` 字段
- [ ] 2.4 `KnowledgeMapper` 增加 `findAllWithPermission()` 带数据权限过滤的查询
- [ ] 2.5 新增 `KnowledgeDocumentRoleMapper` 管理文档-角色关联

## 3. 后端 — 权限框架基础设施

- [ ] 3.1 新建 `PermissionService`：获取用户权限列表、校验权限
- [ ] 3.2 新建 `RoleService`：角色 CRUD、权限分配
- [ ] 3.3 自定义 `UserDetailsService`：加载用户时同时加载角色和权限
- [ ] 3.4 自定义 `PermissionEvaluator`：支持 `hasPermission('module:resource:action')` 表达式
- [ ] 3.5 `SecurityConfig` 配置 `@EnableMethodSecurity`，注册 PermissionEvaluator
- [ ] 3.6 JWT 登录响应中增加 `permissions` 字段，返回用户权限编码列表

## 4. 后端 — 数据权限

- [ ] 4.1 新增 `DataPermissionContext`：线程内保存当前用户数据权限信息
- [ ] 4.2 新增 `DataPermissionInterceptor`（MyBatis 拦截器）：自动为知识库查询注入数据权限 SQL 过滤
- [ ] 4.3 `KnowledgeService.search()` 增加数据权限过滤逻辑
- [ ] 4.4 `knowledgeSearch` 工具调用时同样注入数据权限过滤
- [ ] 4.5 admin 角色跳过数据权限过滤

## 5. 后端 — 管理接口

- [ ] 5.1 `RoleController`：角色 CRUD + 分配权限
- [ ] 5.2 `PermissionController`：权限列表查询
- [ ] 5.3 `UserController` 增加：分配/撤销用户角色
- [ ] 5.4 `KnowledgeController` 增加：设置文档访问范围
- [ ] 5.5 所有管理接口加权限注解（如 `@PreAuthorize("hasPermission('system:role:manage')")`）

## 6. 前端 — 权限基础设施

- [ ] 6.1 `authStore` 增加 `permissions` 字段，登录时从后端获取
- [ ] 6.2 新增 `hasPermission(code)` 工具函数：判断是否拥有指定权限
- [ ] 6.3 新增 `v-permission` 自定义指令：按钮级权限控制
- [ ] 6.4 路由配置增加 `meta.permission` 字段，声明路由所需菜单权限
- [ ] 6.5 路由守卫增加权限校验，无权限跳转 403
- [ ] 6.6 侧边栏菜单根据权限动态生成（过滤无权限项）

## 7. 前端 — 角色管理页面

- [ ] 7.1 角色列表页：展示所有角色、权限数量、操作按钮
- [ ] 7.2 角色编辑弹窗：菜单权限树 + 操作权限勾选 + 数据权限范围设置
- [ ] 7.3 新建/删除角色
- [ ] 7.4 内置角色（admin）标记为不可删除

## 8. 前端 — 用户角色分配

- [ ] 8.1 用户列表增加"角色"列
- [ ] 8.2 用户编辑弹窗增加角色选择（多选）
- [ ] 8.3 分配角色后提示用户重新登录生效

## 9. 前端 — 知识库数据权限

- [ ] 9.1 文档列表增加"访问范围"列（公开/私有/角色）
- [ ] 9.2 文档编辑增加访问范围设置
- [ ] 9.3 role_based 模式下的角色选择器
- [ ] 9.4 私有文档显示"仅自己可见"标记

## 10. 后端接口权限接入（逐模块）

- [ ] 10.1 知识库模块：所有接口加权限注解（create / read / update / delete）
- [ ] 10.2 对话模块：基础对话权限（默认放开，管理接口加控制）
- [ ] 10.3 管理模块：用户管理、角色管理、系统设置接口加权限注解
- [ ] 10.4 其他模块：按优先级逐步接入

## 11. Agent Skill 权限

- [ ] 11.1 `SkillResolver` 增加用户权限参数，解析 skill 后过滤无权限的工具
- [ ] 11.2 `AgentFactory.create()` 增加 userId 参数，传入权限信息用于 Toolkit 过滤
- [ ] 11.3 Toolkit 执行入口增加权限二次校验，防止 Prompt 注入绕过
- [ ] 11.4 `ChatController` 对话接口增加 skill 权限校验：用户无对应 skill 权限时返回 403
- [ ] 11.5 内置技能权限初始化：general-chat、source-code-analysis、task-management、knowledge-qa
- [ ] 11.6 user 角色默认分配 general-chat 和 knowledge-qa 技能权限
- [ ] 11.7 admin 角色拥有全部技能和工具权限

## 12. 前端 — Agent Skill 权限

- [ ] 12.1 对话页面技能选择器根据权限过滤，只显示有权限的 skill
- [ ] 12.2 角色管理页面增加 Agent Skill 权限配置 Tab
- [ ] 12.3 权限配置支持按 skill 粒度勾选（粗粒度）
- [ ] 12.4 权限配置支持按工具粒度勾选（细粒度，高级功能）

## 13. 集成验证

- [ ] 13.1 后端编译通过
- [ ] 13.2 前端构建通过
- [ ] 13.3 菜单权限验证：admin 看到全部菜单，user 只看到授权菜单
- [ ] 13.4 按钮权限验证：无权限的按钮不显示，直接调接口返回 403
- [ ] 13.5 数据权限验证：private 文档仅创建者可见，role_based 仅指定角色可见，public 全员可见
- [ ] 13.6 角色管理验证：创建角色 → 分配权限 → 用户获得角色 → 权限生效
- [ ] 13.7 Agent Skill 权限验证：无权限的 skill 不出现在选择器中，后端也拒绝创建
- [ ] 13.8 工具级权限验证：Prompt 注入无法绕过权限，执行前二次校验拦截
- [ ] 13.9 向后兼容验证：升级后已有用户默认可用，已有文档默认为公开
