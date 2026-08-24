import { useState } from 'react'
import { Button, Card, Form, Input, message } from 'antd'
import { LockOutlined, RobotOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'

export default function Login() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const onFinish = ({ username }) => {
    setLoading(true)
    // Demo login: any credentials are accepted, stored locally only.
    localStorage.setItem('jarvis_user', JSON.stringify({ name: username }))
    setTimeout(() => {
      setLoading(false)
      message.success('登录成功')
      navigate('/dashboard', { replace: true })
    }, 400)
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #2f54eb 130%)',
      }}
    >
      <Card
        style={{ width: 380, boxShadow: '0 12px 40px rgba(0,0,0,0.35)' }}
        styles={{ body: { padding: 32 } }}
      >
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <RobotOutlined style={{ fontSize: 44, color: '#2f54eb' }} />
          <h1 style={{ marginTop: 12, fontSize: 22, fontWeight: 700 }}>JARVIS 管理后台</h1>
          <p style={{ color: '#888', marginTop: 4 }}>AI 对话 · 任务 · 智能体管理</p>
        </div>
        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名（任意值）" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码（任意值）" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" block loading={loading}>
              登 录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
