import axios from 'axios'
import { message } from 'antd'

// ======================================================================
// 全局 axios 实例 + 请求/响应拦截器
// 所有需要认证的 API 都通过 http 实例调用：
//   - 自动在 header 注入 Authorization: Bearer <token>
//   - 401 自动清理本地登录态并跳转登录页
// ======================================================================

const TOKEN_KEY = 'jarvis_token'
const USER_KEY = 'jarvis_user'

/** 存储：获取/写入/移除 token 与 user */
export const authStore = {
  getToken() {
    return localStorage.getItem(TOKEN_KEY) || ''
  },
  setToken(token) {
    localStorage.setItem(TOKEN_KEY, token)
  },
  clearToken() {
    localStorage.removeItem(TOKEN_KEY)
  },
  getUser() {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    } catch {
      return null
    }
  },
  setUser(user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clearUser() {
    localStorage.removeItem(USER_KEY)
  },
  /** 清理所有登录态（401 / 主动退出） */
  logout() {
    this.clearToken()
    this.clearUser()
  },
  /** 是否已登录（仅本地判断，token 合法性由后端校验） */
  isLoggedIn() {
    return !!this.getToken()
  },
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

// ---------- 请求拦截器：自动注入 Bearer Token ----------
http.interceptors.request.use((config) => {
  const token = authStore.getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ---------- 响应拦截器：统一处理 401 / 错误提示 ----------
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const detail = error?.response?.data?.detail

    if (status === 401) {
      // 未登录或 token 过期 → 清理本地数据
      authStore.logout()
      message.error(detail || '登录已过期，请重新登录')
      // 只有非登录页才跳转，避免登录页自己重定向
      if (location.pathname !== '/login') {
        location.replace('/login')
      }
      return Promise.reject(error)
    }

    if (status === 403) {
      message.error(detail || '权限不足，当前角色无权执行此操作')
      return Promise.reject(error)
    }

    if (status === 400) {
      // 参数/业务校验失败，detail 是后端返回的具体原因，这里只冒泡不弹
      // 由各页面自行处理展示（如表单字段错误）
      return Promise.reject(error)
    }

    // 其他：429 / 500 等 → 弹出错误
    const msg = detail || error?.response?.data?.error || error.message || '请求失败'
    message.error(msg)
    return Promise.reject(error)
  }
)

// ======================================================================
// 认证相关 API
// ======================================================================
export const authApi = {
  /**
   * 用户登录
   * POST /api/auth/login
   * @returns {{ token, tokenType, expiresIn, user: {id,username,nickname,email,role,avatarUrl} }}
   */
  login: ({ username, password }) =>
    http.post('/auth/login', { username, password }).then((r) => r.data),

  /**
   * 用户注册
   * POST /api/auth/register
   * @returns 同登录响应结构
   */
  register: ({ username, password, nickname, email }) =>
    http.post('/auth/register', { username, password, nickname, email }).then((r) => r.data),

  /**
   * 获取当前登录用户信息（基于请求头 token）
   * GET /api/auth/me
   */
  me: () => http.get('/auth/me').then((r) => r.data),
}

// ======================================================================
// 任务 API
// ======================================================================
export const taskApi = {
  list: () => http.get('/tasks').then((r) => r.data),
  get: (id) => http.get(`/tasks/${id}`).then((r) => r.data),
  create: (data) => http.post('/tasks', data).then((r) => r.data),
  update: (id, data) => http.put(`/tasks/${id}`, data).then((r) => r.data),
  remove: (id) => http.delete(`/tasks/${id}`),
}

// ======================================================================
// MCP 工具 API
// ======================================================================
export const mcpApi = {
  overview: () => http.get('/mcp').then((r) => r.data),
}

// ======================================================================
// 知识库 API
// ======================================================================
export const knowledgeApi = {
  list: () => http.get('/knowledge/documents').then((r) => r.data),
  get: (id) => http.get(`/knowledge/documents/${id}`).then((r) => r.data),
  create: (data) => http.post('/knowledge/documents', data).then((r) => r.data),
  remove: (id) => http.delete(`/knowledge/documents/${id}`),
  search: (query, topK) =>
    http.post('/knowledge/search', { query, topK }).then((r) => r.data),
  stats: () => http.get('/knowledge/stats').then((r) => r.data),
}

// ======================================================================
// RAG 评测中心 API（只读历史 + 候选池）
// ======================================================================
export const evalApi = {
  /** 评测历史列表（runId 升序即时间序）；目录缺失返回 { items: [] } */
  history: () => http.get('/knowledge/eval/history').then((r) => r.data),

  /** 单次详情：{ summary, previous, diff }；diff=null 表示基线首次建立 */
  historyDetail: (runId) =>
    http.get(`/knowledge/eval/history/${runId}`).then((r) => r.data),

  /** 候选池列表：{ total, page, size, items }；status: pending/promoted/discarded */
  candidates: ({ status = 'pending', page = 1, size = 20 } = {}) =>
    http
      .get('/knowledge/eval/candidates', { params: { status, page, size } })
      .then((r) => r.data),

  /** 提交候选（Chat 👎 / 手动）。409 表示相同问题已在池中（data.existingId） */
  submitCandidate: ({ question, note, source, chatRef }) =>
    http
      .post('/knowledge/eval/candidates', { question, note, source, chatRef })
      .then((r) => r.data),

  /** 转正：补全标注 → 写入标注集 → 置 promoted */
  promoteCandidate: (id, payload) =>
    http.post(`/knowledge/eval/candidates/${id}/promote`, payload).then((r) => r.data),

  /** 丢弃候选 */
  discardCandidate: (id) =>
    http.post(`/knowledge/eval/candidates/${id}/discard`).then((r) => r.data),
}

// ======================================================================
// SSE 流式聊天（直接用 fetch，不走 axios；同样注入 token）
// ======================================================================
/**
 * Stream chat reply via SSE (POST /api/agent/chat/stream).
 * Yields { event, data } frames: event ∈
 *   'message'(默认文本增量)
 * | 'sources'(引用的知识库片段 JSON)
 * | 'error'(后端错误帧)
 * | 'done'(结束标记)
 * Abort via AbortController.
 */
export async function* streamChat(message, signal) {
  const token = authStore.getToken()
  const resp = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ message }),
    signal,
  })

  // 非 2xx：尝试解析后端返回的 detail
  if (!resp.ok) {
    if (resp.status === 401) {
      authStore.logout()
      message.error('登录已过期，请重新登录')
      location.replace('/login')
    }
    let detail = `HTTP ${resp.status} ${resp.statusText}`
    try {
      const json = await resp.json()
      if (json.detail) detail = json.detail
    } catch {/* 不是 JSON 就忽略 */}
    throw new Error(detail)
  }

  if (!resp.body) {
    throw new Error('服务器未返回响应体')
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)

      const lines = frame.split('\n')
      const eventLine = lines.find((l) => l.startsWith('event:'))
      const dataLines = lines.filter((l) => l.startsWith('data:'))
      if (!dataLines.length) continue

      const payload = dataLines.map((l) => l.slice(5).trim()).join('\n')
      if (!payload) continue

      yield {
        event: eventLine ? eventLine.slice(6).trim() : 'message',
        data: payload,
      }
    }
  }
}
