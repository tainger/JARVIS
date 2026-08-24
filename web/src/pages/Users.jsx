import { Alert, Avatar, Card, Space, Table, Tag, Typography } from 'antd'
import { UserOutlined } from '@ant-design/icons'

const { Title } = Typography

// Mock data — backend user API not implemented yet.
const mockUsers = [
  { id: 1, name: 'admin', email: 'admin@jarvis.local', role: '超级管理员', status: 'active' },
  { id: 2, name: 'operator', email: 'operator@jarvis.local', role: '运营人员', status: 'active' },
  { id: 3, name: 'analyst', email: 'analyst@jarvis.local', role: '数据分析师', status: 'disabled' },
]

export default function Users() {
  const columns = [
    {
      title: '用户',
      dataIndex: 'name',
      render: (v, r) => (
        <Space>
          <Avatar size="small" icon={<UserOutlined />} />
          <span>{v}</span>
        </Space>
      ),
    },
    { title: '邮箱', dataIndex: 'email' },
    { title: '角色', dataIndex: 'role', render: (v) => <Tag color="blue">{v}</Tag> },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) =>
        v === 'active' ? <Tag color="success">启用</Tag> : <Tag color="default">禁用</Tag>,
    },
  ]

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>
        用户管理
      </Title>
      <Alert
        type="info"
        showIcon
        message="占位页面"
        description="后端暂未提供用户管理接口，当前展示的是演示数据。可在后续接入 Spring Security / 用户服务。"
        style={{ marginBottom: 16 }}
      />
      <Card>
        <Table rowKey="id" columns={columns} dataSource={mockUsers} pagination={false} />
      </Card>
    </div>
  )
}
