// 全局设计常量：3D 黏土立体风（Claymorphism）
// 色板与阴影集中在这一处，所有页面/组件从这里取值，禁止散落硬编码。

// ---------- 色彩系统（柔和粉彩 + 黏土） ----------
export const CLAY = {
  base: '#F5F7FA', // 页面底色：柔和粉彩灰白
  purple: '#6C5CE7', // 主强调：CTA / 标题
  purpleTint: '#F3F0FF', // 紫染卡片底
  coral: '#FF7675', // 次强调：装饰
  coralTint: '#FFF0F0',
  mustard: '#FDCB6E', // 点缀：高亮
  mustardTint: '#FFF9E8',
  mint: '#00B894', // 成功 / 标签
  mintTint: '#E8FAF5',
  ink: '#3A3A52', // 深色文字（避免纯黑，保持柔软感）
  inkSoft: '#8A8AA3', // 次要文字
}

export const BRAND = {
  primary: CLAY.purple,
  // CTA 渐变：紫 → 珊瑚，用于大按钮与对话气泡
  primaryGradient: 'linear-gradient(135deg, #6C5CE7 0%, #8E7CF3 60%, #FF7675 140%)',
}

// ---------- 黏土三层阴影 ----------
// 顶面亮（内高光）+ 内侧暗缘 + 底部柔影，叠出"可捏"的立体感。
export const CLAY_SHADOW = {
  // 浮起的卡片 / 大按钮
  raised:
    'inset 0 -4px 8px rgba(255,255,255,0.8), inset 0 4px 8px rgba(0,0,0,0.05), 0 12px 24px rgba(108,92,231,0.16), 0 4px 8px rgba(0,0,0,0.04)',
  // hover：上浮后阴影更深
  raisedHover:
    'inset 0 -4px 8px rgba(255,255,255,0.8), inset 0 4px 8px rgba(0,0,0,0.05), 0 20px 36px rgba(108,92,231,0.24), 0 8px 16px rgba(0,0,0,0.06)',
  // 凹陷（按下 / 输入框 / 气泡内槽）
  inset:
    'inset 0 3px 8px rgba(108,92,231,0.10), inset 0 -2px 4px rgba(255,255,255,0.9)',
  // 图标盒 / 小徽章
  small:
    'inset 0 -2px 4px rgba(255,255,255,0.8), inset 0 2px 4px rgba(0,0,0,0.05), 0 6px 14px rgba(108,92,231,0.18)',
}

export const LAYOUT = {
  siderWidth: 224,
  headerHeight: 64,
  bgLayout: CLAY.base,
  contentPadding: 28,
}

// 圆角规范：卡片 28px / 图标盒 20px / 控件药丸 999px
export const RADIUS = {
  card: 28,
  iconBox: 20,
  pill: 999,
}
