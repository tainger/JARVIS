import { useState } from 'react'
import { Button, Card, Form, Input, message, Segmented, Alert } from 'antd'
import {
  LockOutlined,
  UserOutlined,
  MailOutlined,
  SmileOutlined,
  SafetyOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'
import { authApi, authStore } from '../api/client'

/**
 * 登录 / 注册 合并页
 * - 顶部 Segmented 切换：登录 / 注册
 * - 黏土风格（无直角，柔和三层阴影，漂浮 blob 装饰）
 * - 连接后端真实 API：POST /api/auth/login  &  /api/auth/register
 * - 成功后将 token + user 存入 localStorage，跳转 dashboard
 */
export default function Login() {
  const [mode, setMode] = useState('login') // 'login' | 'register'
  const [loading, setLoading] = useState(false)
  const [formError, setFormError] = useState('') // 400 级别的后端错误，展示在表单顶部
  const navigate = useNavigate()

  // ---------- 登录提交 ----------
  const handleLogin = async (values) => {
    setLoading(true)
    setFormError('')
    try {
      const resp = await authApi.login({
        username: values.username.trim(),
        password: values.password,
      })
      // 保存登录态
      authStore.setToken(resp.token)
      authStore.setUser(resp.user)
      message.success(`欢迎回来，${resp.user.nickname || resp.user.username} 👋`)
      navigate('/dashboard', { replace: true })
    } catch (e) {
      const status = e?.response?.status
      const detail = e?.response?.data?.detail
      if (status === 400) {
        setFormError(detail || '登录失败')
      }
      // 其他错误（429/500）已在 axios 拦截器统一弹出
    } finally {
      setLoading(false)
    }
  }

  // ---------- 注册提交 ----------
  const handleRegister = async (values) => {
    setLoading(true)
    setFormError('')
    try {
      const resp = await authApi.register({
        username: values.username.trim(),
        password: values.password,
        nickname: values.nickname?.trim() || undefined,
        email: values.email?.trim() || undefined,
      })
      // 注册即登录：直接保存登录态
      authStore.setToken(resp.token)
      authStore.setUser(resp.user)
      message.success(`注册成功！欢迎加入 JARVIS，${resp.user.nickname || resp.user.username} 🎉`)
      navigate('/dashboard', { replace: true })
    } catch (e) {
      const status = e?.response?.status
      const detail = e?.response?.data?.detail
      if (status === 400) {
        setFormError(detail || '注册失败')
      }
      // 其他错误已在拦截器处理
    } finally {
      setLoading(false)
    }
  }

  // ---------- 切换模式时清空错误 ----------
  const switchMode = (next) => {
    setMode(next)
    setFormError('')
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
        padding: 24,
      }}
    >
      {/* ======== 漂浮黏土装饰 ======== */}
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

      {/* ======== 主卡片 ======== */}
      <Card
        style={{
          width: 440,
          maxWidth: '100%',
          borderRadius: 32,
          boxShadow: CLAY_SHADOW.raised,
          position: 'relative',
          zIndex: 1,
        }}
        styles={{ body: { padding: 32 } }}
      >
        {/* 品牌区 */}
        <div style={{ textAlign: 'center', marginBottom: 20 }}>
          <div
            className="clay-icon-box clay-float"
            style={{
              width: 76, height: 76, fontSize: 40,
              background: CLAY.purpleTint, margin: '0 auto 16px',
            }}
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
            AI 对话 · 任务 · 智能体 · 知识库
          </p>
        </div>

        {/* 模式切换（登录 / 注册） */}
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 24 }}>
          <Segmented
            value={mode}
            onChange={switchMode}
            options={[
              { label: '🔑 登 录', value: 'login' },
              { label: '✨ 注 册', value: 'register' },
            ]}
            style={{
              padding: 4,
              borderRadius: 999,
              background: CLAY.base,
              boxShadow: CLAY_SHADOW.inset,
              fontWeight: 700,
            }}
          />
        </div>

        {/* 后端返回的 400 错误展示 */}
        {formError && (
          <Alert
            type="error"
            showIcon
            message={formError}
            style={{ borderRadius: 16, marginBottom: 20 }}
            closable
            onClose={() => setFormError('')}
          />
        )}

        {/* ===== 登录表单 ===== */}
        {mode === 'login' && (
          <Form
            name="login-form"
            size="large"
            onFinish={handleLogin}
            initialValues={{ username: '', password: '' }}
          >
            <Form.Item
              name="username"
              validateTrigger="onBlur"
              rules={[
                { required: true, message: '请输入用户名' },
                { min: 3, max: 32, message: '用户名长度为 3-32 个字符' },
              ]}
            >
              <Input
                allowClear
                prefix={<UserOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="请输入用户名"
                autoComplete="username"
              />
            </Form.Item>
            <Form.Item
              name="password"
              validateTrigger="onBlur"
              rules={[
                { required: true, message: '请输入密码' },
                { min: 6, max: 64, message: '密码长度为 6-64 个字符' },
              ]}
            >
              <Input.Password
                prefix={<LockOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button
                type="primary"
                htmlType="submit"
                block
                loading={loading}
                style={{
                  height: 48, fontWeight: 800, fontSize: 15,
                  background: BRAND.primaryGradient,
                  border: 'none',
                }}
              >
                {loading ? '登 录 中...' : '登  录'}
              </Button>
            </Form.Item>

            <p
              style={{
                textAlign: 'center',
                marginTop: 16,
                color: CLAY.inkSoft,
                fontWeight: 600,
                fontSize: 13,
              }}
            >
              还没有账号？
              <a
                style={{ color: BRAND.primary, marginLeft: 6 }}
                onClick={(e) => { e.preventDefault(); switchMode('register') }}
              >
                立即注册 →
              </a>
            </p>
          </Form>
        )}

        {/* ===== 注册表单 ===== */}
        {mode === 'register' && (
          <Form
            name="register-form"
            size="large"
            onFinish={handleRegister}
            initialValues={{ username: '', password: '', nickname: '', email: '' }}
          >
            <Form.Item
              name="username"
              validateTrigger="onBlur"
              rules={[
                { required: true, message: '请设置用户名' },
                { min: 3, max: 32, message: '用户名长度为 3-32 个字符' },
                {
                  pattern: /^[A-Za-z0-9_.]+$/,
                  message: '只能包含字母、数字、下划线、点',
                },
              ]}
              extra="3-32 个字符，限字母/数字/_/. （例：zhangsan_01）"
            >
              <Input
                allowClear
                prefix={<UserOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="设置用户名"
                autoComplete="username"
              />
            </Form.Item>

            <Form.Item
              name="password"
              validateTrigger="onBlur"
              rules={[
                { required: true, message: '请设置密码' },
                { min: 6, max: 64, message: '密码长度为 6-64 个字符' },
              ]}
              extra="至少 6 位，越长越安全"
            >
              <Input.Password
                prefix={<LockOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="设置登录密码"
                autoComplete="new-password"
              />
            </Form.Item>

            <Form.Item
              name="confirmPassword"
              dependencies={['password']}
              validateTrigger="onBlur"
              rules={[
                { required: true, message: '请再次输入密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) {
                      return Promise.resolve()
                    }
                    return Promise.reject(new Error('两次输入的密码不一致'))
                  },
                }),
              ]}
            >
              <Input.Password
                prefix={<SafetyOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="确认密码"
                autoComplete="new-password"
              />
            </Form.Item>

            <Form.Item
              name="nickname"
              validateTrigger="onBlur"
              rules={[{ max: 32, message: '昵称最多 32 个字符' }]}
            >
              <Input
                allowClear
                prefix={<SmileOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="昵称（可选，显示用）"
              />
            </Form.Item>

            <Form.Item
              name="email"
              validateTrigger="onBlur"
              rules={[
                {
                  type: 'email',
                  message: '邮箱格式不正确',
                },
              ]}
            >
              <Input
                allowClear
                prefix={<MailOutlined style={{ color: CLAY.inkSoft }} />}
                placeholder="邮箱（可选）"
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button
                type="primary"
                htmlType="submit"
                block
                loading={loading}
                style={{
                  height: 48, fontWeight: 800, fontSize: 15,
                  background: BRAND.primaryGradient,
                  border: 'none',
                }}
              >
                {loading ? '注 册 中...' : '创建账号并登录'}
              </Button>
            </Form.Item>

            <p
              style={{
                textAlign: 'center',
                marginTop: 16,
                color: CLAY.inkSoft,
                fontWeight: 600,
                fontSize: 13,
              }}
            >
              已有账号？
              <a
                style={{ color: BRAND.primary, marginLeft: 6 }}
                onClick={(e) => { e.preventDefault(); switchMode('login') }}
              >
                去登录 →
              </a>
            </p>
          </Form>
        )}
      </Card>
    </div>
  )
}
