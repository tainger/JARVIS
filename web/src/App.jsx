import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AdminLayout from './layouts/AdminLayout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Chat from './pages/Chat'
import Tasks from './pages/Tasks'
import Users from './pages/Users'
import Agents from './pages/Agents'
import Knowledge from './pages/Knowledge'
import { authStore, authApi } from './api/client'

/**
 * 路由守卫：需要登录
 * - 本地没有 token → 直接跳 /login
 * - 有 token → 尝试请求 /auth/me 校验后端有效性；
 *   校验成功可同步 user 信息（角色/昵称等）；
 *   校验失败（401）→ 由 axios 拦截器自动清理 + 跳转登录。
 */
function RequireAuth({ children }) {
  const [ready, setReady] = useState(false)

  useEffect(() => {
    ;(async () => {
      if (!authStore.isLoggedIn()) {
        setReady(true)
        return
      }
      // 尝试从后端重新拉取一次当前用户信息，刷新本地缓存（失败不阻塞渲染）
      try {
        const me = await authApi.me()
        authStore.setUser(me)
      } catch {
        // 401 已由拦截器处理跳转 /login
      } finally {
        setReady(true)
      }
    })()
  }, [])

  if (!ready) {
    // 白屏极短时间，直到首次身份校验完成
    return null
  }

  if (!authStore.isLoggedIn()) {
    return <Navigate to="/login" replace />
  }
  return children
}

/**
 * 反向守卫：登录页如果已登录，直接跳转到仪表盘（避免重复登录）
 */
function RedirectIfLoggedIn({ children }) {
  if (authStore.isLoggedIn()) {
    return <Navigate to="/dashboard" replace />
  }
  return children
}

export default function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RedirectIfLoggedIn>
            <Login />
          </RedirectIfLoggedIn>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AdminLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="chat" element={<Chat />} />
        <Route path="tasks" element={<Tasks />} />
        <Route path="users" element={<Users />} />
        <Route path="agents" element={<Agents />} />
        <Route path="knowledge" element={<Knowledge />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
