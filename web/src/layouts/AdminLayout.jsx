import { useMemo, useState } from 'react'
import { Avatar, Dropdown, Layout, Menu, Space, Tag } from 'antd'
import {
  DashboardOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
  RobotOutlined,
  SettingOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  UserOutlined,
  BookOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { BRAND, CLAY, CLAY_SHADOW, LAYOUT, RADIUS } from '../theme'

const { Sider, Header, Content, Footer } = Layout

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/chat', icon: <MessageOutlined />, label: 'AI 对话' },
  { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务管理' },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/agents', icon: <RobotOutlined />, label: '智能体管理' },
  { key: '/knowledge', icon: <BookOutlined />, label: '知识库' },
]

// 漂浮黏土装饰：低饱和圆块，均匀铺在内容后面，营造"软"的空间感
function FloatingBlobs() {
  return (
    <div aria-hidden style={{ position: 'fixed', inset: 0, overflow: 'hidden', zIndex: 0, pointerEvents: 'none' }}>
      <div
        className="clay-float-slow"
        style={{
          position: 'absolute', top: '12%', right: '6%',
          width: 120, height: 120, borderRadius: '36% 64% 55% 45% / 50% 45% 55% 50%',
          background: CLAY.purpleTint, opacity: 0.9,
          boxShadow: CLAY_SHADOW.raised,
        }}
      />
      <div
        className="clay-float"
        style={{
          position: 'absolute', top: '55%', right: '14%',
          width: 64, height: 64, borderRadius: '45% 55% 60% 40% / 55% 45% 55% 45%',
          background: CLAY.coralTint, opacity: 0.85,
          boxShadow: CLAY_SHADOW.small, animationDelay: '0.8s',
        }}
      />
      <div
        className="clay-float-slow"
        style={{
          position: 'absolute', bottom: '10%', left: '30%',
          width: 90, height: 90, borderRadius: '55% 45% 40% 60% / 45% 60% 40% 55%',
          background: CLAY.mustardTint, opacity: 0.8,
          boxShadow: CLAY_SHADOW.small, animationDelay: '1.6s',
        }}
      />
      <div
        className="clay-float"
        style={{
          position: 'absolute', top: '30%', left: '6%',
          width: 48, height: 48, borderRadius: '40% 60% 55% 45% / 60% 40% 60% 40%',
          background: CLAY.mintTint, opacity: 0.9,
          boxShadow: CLAY_SHADOW.small, animationDelay: '2.2s',
        }}
      />
    </div>
  )
}

export default function AdminLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()

  const user = useMemo(() => {
    try {
      return JSON.parse(localStorage.getItem('jarvis_user') || 'null')
    } catch {
      return null
    }
  }, [])

  const handleLogout = () => {
    localStorage.removeItem('jarvis_user')
    navigate('/login', { replace: true })
  }

  const siderWidth = LAYOUT.siderWidth

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <FloatingBlobs />
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        width={siderWidth}
        theme="light"
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          insetInlineStart: 0,
          top: 0,
          bottom: 0,
          zIndex: 20,
          margin: 12,
          height: 'calc(100vh - 24px)',
          borderRadius: 28,
          boxShadow: CLAY_SHADOW.raised,
        }}
      >
        <div
          style={{
            height: 72,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 10,
            fontSize: collapsed ? 20 : 17,
            fontWeight: 800,
            letterSpacing: '-0.02em',
            color: CLAY.ink,
          }}
        >
          <span
            className="clay-icon-box"
            style={{ width: 40, height: 40, fontSize: 20, background: CLAY.purpleTint }}
          >
            <RobotOutlined style={{ color: BRAND.primary }} />
          </span>
          {!collapsed && <span>JARVIS</span>}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout
        style={{
          marginInlineStart: collapsed ? 92 : siderWidth + 12,
          transition: 'margin-inline-start 0.2s',
          position: 'relative',
          zIndex: 1,
        }}
      >
        <Header
          style={{
            padding: '0 28px',
            height: LAYOUT.headerHeight,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
        >
          <span
            role="button"
            tabIndex={0}
            className="clay-icon-box clay-card-hover"
            style={{ width: 40, height: 40, fontSize: 16, background: '#fff', cursor: 'pointer' }}
            onClick={() => setCollapsed(!collapsed)}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <Space size="middle">
            <Tag
              icon={<RobotOutlined />}
              style={{
                background: CLAY.mintTint,
                color: CLAY.mint,
                fontWeight: 700,
                boxShadow: CLAY_SHADOW.small,
              }}
            >
              Agent 在线
            </Tag>
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'profile',
                    icon: <SettingOutlined />,
                    label: '个人设置',
                    disabled: true,
                  },
                  { type: 'divider' },
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: handleLogout,
                  },
                ],
              }}
            >
              <Space
                style={{
                  cursor: 'pointer',
                  background: '#fff',
                  borderRadius: RADIUS.pill,
                  padding: '6px 14px 6px 6px',
                  boxShadow: CLAY_SHADOW.small,
                }}
              >
                <Avatar
                  size={32}
                  icon={<UserOutlined />}
                  style={{ background: BRAND.primary, fontWeight: 700 }}
                />
                <span style={{ fontWeight: 700, color: CLAY.ink }}>
                  {user?.name || 'admin'}
                </span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content
          style={{
            margin: LAYOUT.contentPadding,
            minHeight: `calc(100vh - ${LAYOUT.headerHeight + LAYOUT.contentPadding * 2 + 48}px)`,
          }}
        >
          <Outlet />
        </Content>
        <Footer style={{ textAlign: 'center', color: CLAY.inkSoft, background: 'transparent' }}>
          JARVIS Admin ©{new Date().getFullYear()} · Powered by Spring Boot & Ant Design
        </Footer>
      </Layout>
    </Layout>
  )
}
