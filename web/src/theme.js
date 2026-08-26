// 全局设计常量：集中管理品牌色与布局尺寸，避免在各页面硬编码。
// 与 main.jsx 的 ConfigProvider theme.token 保持一致。
export const BRAND = {
  primary: '#2f54eb',
  primaryGradient: 'linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #2f54eb 130%)',
}

export const LAYOUT = {
  siderWidth: 220,
  headerHeight: 56,
  // Pro 风格：整体内容区使用浅灰底，卡片浮于其上。
  bgLayout: '#f0f2f5',
  contentPadding: 24,
}
