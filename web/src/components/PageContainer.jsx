import { Breadcrumb, Space, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { CLAY, CLAY_SHADOW, RADIUS } from '../theme'

const { Title, Text } = Typography

/**
 * 仿 Ant Design Pro 的 PageContainer（黏土风）：
 * 页头是一块 28px 圆角黏土卡片，支持 emoji 图标盒。
 *
 * props:
 * - title: 页面标题
 * - emoji: 标题左侧的 emoji 图标盒（可选，如 '📋'）
 * - emojiBg: 图标盒底色（默认紫染）
 * - subTitle: 标题右侧的次要说明（可选）
 * - breadcrumb: [{ title, to? }] 面包屑项，最后一项通常为当前页
 * - extra: 页头右上角操作区（如按钮组）
 * - description: 标题下方的一段描述文字（可选）
 */
export default function PageContainer({
  title,
  emoji,
  emojiBg,
  subTitle,
  breadcrumb = [],
  extra,
  description,
  children,
}) {
  const crumbItems = breadcrumb.map((item) => ({
    title: item.to ? <Link to={item.to}>{item.title}</Link> : item.title,
  }))

  return (
    <div>
      <div
        style={{
          background: '#fff',
          padding: '20px 28px',
          borderRadius: RADIUS.card,
          marginBottom: 20,
          boxShadow: CLAY_SHADOW.raised,
        }}
      >
        {crumbItems.length > 0 && (
          <Breadcrumb items={crumbItems} style={{ marginBottom: 10 }} />
        )}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
            flexWrap: 'wrap',
          }}
        >
          <Space size={14} align="center">
            {emoji && (
              <span
                className="clay-icon-box clay-float-slow"
                style={{ background: emojiBg || CLAY.purpleTint }}
              >
                {emoji}
              </span>
            )}
            <Space align="baseline" size={12}>
              <Title
                level={3}
                style={{
                  margin: 0,
                  fontWeight: 800,
                  letterSpacing: '-0.02em',
                  color: CLAY.ink,
                }}
              >
                {title}
              </Title>
              {subTitle && (
                <Text type="secondary" style={{ color: CLAY.inkSoft }}>
                  {subTitle}
                </Text>
              )}
            </Space>
          </Space>
          {extra && <Space wrap>{extra}</Space>}
        </div>
        {description && (
          <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
            {description}
          </Text>
        )}
      </div>
      {children}
    </div>
  )
}
