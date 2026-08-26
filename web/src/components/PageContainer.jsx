import { Breadcrumb, Space, Typography } from 'antd'
import { Link } from 'react-router-dom'

const { Title, Text } = Typography

/**
 * 仿 Ant Design Pro 的 PageContainer：统一页头区域（面包屑 + 标题 + 描述 + 操作），
 * 让各业务页面视觉一致。children 为页面主体内容。
 *
 * props:
 * - title: 页面标题
 * - subTitle: 标题右侧的次要说明（可选）
 * - breadcrumb: [{ title, to? }] 面包屑项，最后一项通常为当前页
 * - extra: 页头右上角操作区（如按钮组）
 * - description: 标题下方的一段描述文字（可选）
 */
export default function PageContainer({
  title,
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
          padding: '16px 24px',
          borderRadius: 8,
          marginBottom: 16,
          boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
        }}
      >
        {crumbItems.length > 0 && (
          <Breadcrumb items={crumbItems} style={{ marginBottom: 8 }} />
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
          <Space align="baseline" size={12}>
            <Title level={4} style={{ margin: 0 }}>
              {title}
            </Title>
            {subTitle && <Text type="secondary">{subTitle}</Text>}
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
