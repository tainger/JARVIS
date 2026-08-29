import { useMemo, useState } from 'react'
import { Avatar, Dropdown, Layout, Menu, Space, Tag, theme } from 'antd'
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
import { BRAND, LAYOUT } from '../theme'

const { Sider, Header, Content, Footer } = Layout

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/chat', icon: <MessageOutlined />, label: 'AI 对话' },
  { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务管理' },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/agents', icon: <RobotOutlined />, label: '智能体管理' },
  { key: '/knowledge', icon: <BookOutlined />, label: '知识库' },
]

export default function AdminLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const { token } = theme.useToken()

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
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        width={siderWidth}
        theme="dark"
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          insetInlineStart: 0,
          top: 0,
          bottom: 0,
          zIndex: 20,
          boxShadow: '2px 0 8px rgba(0,0,0,0.15)',
        }}
      >
        <div
          style={{
            height: LAYOUT.headerHeight,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            color: '#fff',
            fontSize: collapsed ? 20 : 17,
            fontWeight: 700,
            letterSpacing: 1,
          }}
        >
          <RobotOutlined style={{ color: BRAND.primary, fontSize: 24 }} />
          {!collapsed && <span>JARVIS Admin</span>}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout
        style={{
          marginInlineStart: collapsed ? 80 : siderWidth,
          transition: 'margin-inline-start 0.2s',
        }}
      >
        <Header
          style={{
            padding: '0 24px',
            height: LAYOUT.headerHeight,
            lineHeight: `${LAYOUT.headerHeight}px`,
            background: token.colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 10,
            boxShadow: '0 1px 4px rgba(0,21,41,0.08)',
          }}
        >
          <span
            role="button"
            tabIndex={0}
            style={{ fontSize: 18, cursor: 'pointer' }}
            onClick={() => setCollapsed(!collapsed)}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <Space size="middle">
            <Tag color="processing" icon={<RobotOutlined />}>
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
              <Space style={{ cursor: 'pointer' }}>
                <Avatar
                  size="small"
                  icon={<UserOutlined />}
                  style={{ background: BRAND.primary }}
                />
                <span>{user?.name || 'admin'}</span>
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
        <Footer style={{ textAlign: 'center', color: token.colorTextSecondary }}>
          JARVIS Admin ©{new Date().getFullYear()} · Powered by Spring Boot & Ant Design
        </Footer>
      </Layout>
    </Layout>
  )
}
