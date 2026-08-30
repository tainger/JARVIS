import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

export const taskApi = {
  list: () => http.get('/tasks').then((r) => r.data),
  get: (id) => http.get(`/tasks/${id}`).then((r) => r.data),
  create: (data) => http.post('/tasks', data).then((r) => r.data),
  update: (id, data) => http.put(`/tasks/${id}`, data).then((r) => r.data),
  remove: (id) => http.delete(`/tasks/${id}`),
}

export const mcpApi = {
  overview: () => http.get('/mcp').then((r) => r.data),
}

export const knowledgeApi = {
  list: () => http.get('/knowledge/documents').then((r) => r.data),
  get: (id) => http.get(`/knowledge/documents/${id}`).then((r) => r.data),
  create: (data) => http.post('/knowledge/documents', data).then((r) => r.data),
  remove: (id) => http.delete(`/knowledge/documents/${id}`),
  search: (query, topK) =>
    http.post('/knowledge/search', { query, topK }).then((r) => r.data),
  stats: () => http.get('/knowledge/stats').then((r) => r.data),
}

/**
 * Stream chat reply via SSE (POST /api/agent/chat/stream).
 * Yields { event, data } frames: event ∈ 'message'(默认文本增量) |
 * 'sources'(引用的知识库片段 JSON) | 'error'(后端错误帧). Abort via AbortController.
 */
export async function* streamChat(message, signal) {
  const resp = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
    signal,
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status} ${resp.statusText}`)
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
      yield { event: eventLine ? eventLine.slice(6).trim() : 'message', data: payload }
    }
  }
}
