import { useState } from 'react'
import { Button, Card, Form, Input, message } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'

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
        position: 'relative',
        overflow: 'hidden',
        background: CLAY.base,
      }}
    >
      {/* 漂浮黏土装饰 */}
      <div
        className="clay-float-slow"
        style={{
          position: 'absolute', top: '10%', left: '12%',
          width: 160, height: 160, borderRadius: '40% 60% 55% 45% / 50% 45% 55% 50%',
          background: CLAY.purpleTint, boxShadow: CLAY_SHADOW.raised,
        }}
      />
      <div
        className="clay-float"
        style={{
          position: 'absolute', top: '18%', right: '14%',
          width: 90, height: 90, borderRadius: '55% 45% 40% 60% / 45% 60% 40% 55%',
          background: CLAY.coralTint, boxShadow: CLAY_SHADOW.small, animationDelay: '0.6s',
        }}
      />
      <div
        className="clay-float-slow"
        style={{
          position: 'absolute', bottom: '12%', right: '20%',
          width: 120, height: 120, borderRadius: '36% 64% 55% 45% / 50% 45% 55% 50%',
          background: CLAY.mintTint, boxShadow: CLAY_SHADOW.raised, animationDelay: '1.2s',
        }}
      />
      <div
        className="clay-float"
        style={{
          position: 'absolute', bottom: '20%', left: '18%',
          width: 64, height: 64, borderRadius: '45% 55% 60% 40% / 55% 45% 55% 45%',
          background: CLAY.mustardTint, boxShadow: CLAY_SHADOW.small, animationDelay: '1.8s',
        }}
      />

      <Card
        style={{ width: 400, borderRadius: 32, boxShadow: CLAY_SHADOW.raised }}
        styles={{ body: { padding: 36 } }}
      >
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <div
            className="clay-icon-box clay-float"
            style={{ width: 76, height: 76, fontSize: 40, background: CLAY.purpleTint, margin: '0 auto 16px' }}
          >
            🤖
          </div>
          <h1
            style={{
              marginTop: 12,
              fontSize: 24,
              fontWeight: 800,
              letterSpacing: '-0.02em',
              color: CLAY.ink,
            }}
          >
            JARVIS 管理后台
          </h1>
          <p style={{ color: CLAY.inkSoft, marginTop: 4, fontWeight: 600 }}>
            AI 对话 · 任务 · 智能体管理
          </p>
        </div>
        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined style={{ color: CLAY.inkSoft }} />} placeholder="用户名（任意值）" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined style={{ color: CLAY.inkSoft }} />} placeholder="密码（任意值）" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              style={{ height: 48, fontWeight: 800, fontSize: 15 }}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
