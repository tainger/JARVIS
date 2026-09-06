# Spec: 对话分析仪表盘

## 需求

### REQ-1: 总览统计卡片

**Given** admin 用户登录系统  
**When** 打开对话分析页面  
**Then** 显示 4 个统计卡片：总会话数、总工具调用数、平均 ReAct 步数/会话、幻觉率（dislike 数/总会话数）  
**And** 每个卡片显示数字 + 标签 + emoji 图标  
**And** 数字精确到小数点后 1 位（幻觉率显示百分比）

### REQ-2: 工具调用频率可视化

**Given** agent_trace 表中有 tool_name 记录  
**When** 页面加载工具频率模块  
**Then** 显示水平柱状图，每条柱代表一个工具  
**And** 柱长按调用次数降序排列  
**And** 柱颜色使用品牌主题色  
**And** 鼠标悬停显示 tooltip：工具名、调用次数、平均耗时、截断次数

### REQ-3: 每日趋势折线图

**Given** 用户选择时间范围（7天/30天/全部）  
**When** 页面请求 daily-trend API  
**Then** 显示双 Y 轴折线图  
**And** 左 Y 轴显示会话数，右 Y 轴显示工具调用数  
**And** X 轴按日期排列，格式为 MM-DD  
**And** 鼠标悬停显示当日详细数据：会话数、工具调用数、截断数、dislike 数

### REQ-4: 技能使用分布饼图

**Given** agent_trace 表中有 tool_name 记录  
**When** 页面加载技能分布模块  
**Then** 显示环形饼图  
**And** 每个扇区代表一种技能（按工具使用组合推断）  
**And** 显示百分比和技能名  
**And** 鼠标悬停显示该技能的会话数

### REQ-5: 工具性能明细表

**Given** agent_trace 表中有 duration_ms 和 is_truncated 记录  
**When** 页面加载明细表  
**Then** 显示 Ant Design Table，列：工具名、调用次数、平均耗时(ms)、最大耗时(ms)、截断次数  
**And** 支持按调用次数和平均耗时排序  
**And** 截断次数 > 0 的行高亮显示

### REQ-6: 时间范围选择

**Given** 页面顶部有时间范围选择器  
**When** 用户切换 7天/30天/全部  
**Then** 总览卡片、每日趋势图数据刷新  
**And** 工具频率和技能分布不受时间范围影响（全局聚合）  
**And** 加载时显示 skeleton 动画

### REQ-7: 权限控制

**Given** 非 admin 用户尝试访问 /api/analytics/*  
**When** 发起请求  
**Then** 返回 403 Forbidden  
**And** 前端路由守卫重定向到 /chat  

### REQ-8: 数据为空时的降级

**Given** 系统无 trace 数据（全新部署）  
**When** 打开对话分析页面  
**Then** 统计卡片显示 0  
**And** 图表区域显示 Empty 占位"暂无分析数据，开始对话后即可生成"  
**And** 不报错
