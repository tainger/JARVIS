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

/**
 * Stream chat reply via SSE (POST /api/agent/chat/stream).
 * Yields text deltas as they arrive. Abort via AbortController.
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
      const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
      if (dataLine) {
        const payload = dataLine.slice(5).trim()
        if (payload) yield payload
      }
    }
  }
}
