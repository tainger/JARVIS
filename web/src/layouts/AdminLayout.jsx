import { useMemo, useState } from 'react'
import { Avatar, Dropdown, Layout, Menu, Space, Tag, theme } from 'antd'
import {
  DashboardOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
  RobotOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'

const { Sider, Header, Content } = Layout

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/chat', icon: <MessageOutlined />, label: 'AI 对话' },
  { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务管理' },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/agents', icon: <RobotOutlined />, label: '智能体管理' },
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

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        width={220}
        theme="dark"
      >
        <div
          style={{
            height: 64,
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
          <RobotOutlined style={{ color: '#2f54eb', fontSize: 24 }} />
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
      <Layout>
        <Header
          style={{
            padding: '0 24px',
            background: token.colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
          }}
        >
          <span
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
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: handleLogout,
                  },
                ],
              }}
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{user?.name || 'admin'}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
