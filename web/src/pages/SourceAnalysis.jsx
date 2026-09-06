import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Input,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import {
  ClearOutlined,
  CodeOutlined,
  LoadingOutlined,
  SendOutlined,
  StopOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { streamChat } from '../api/client'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'

const { TextArea } = Input

function normalizeMarkdown(text) {
  return text
    .replace(/^(#{1,6})(?=[^#\s])/gm, '$1 ')
    .replace(/^(\s*)(\d+)\.(?=\S)/gm, '$1$2. ')
}

const suggestions = [
  'Nacos 的服务注册逻辑在哪个类？',
  'DistroProtocol 是怎么同步数据的？',
  'ConfigOperationService 的 publishConfig 做了什么？',
]

export default function SourceAnalysis() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState('')
  const abortRef = useRef(null)
  const bottomRef = useRef(null)

  const scrollToBottom = () => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }

  useEffect(() => {
    scrollToBottom()
  }, [messages])

  const send = async (text) => {
    const content = (text ?? input).trim()
    if (!content || streaming) return
    setInput('')
    setError('')

    const userMsg = { role: 'user', content }
    const botMsg = { role: 'assistant', content: '', reasoning: '', trace: [] }
    setMessages((prev) => [...prev, userMsg, botMsg])
    setStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      let acc = ''
      let reasoningAcc = ''
      let traceAcc = []
      for await (const { event, data } of streamChat(content, controller.signal, {
        mode: 'source-analysis',
      })) {
        if (event === 'conversation') {
          // 忽略会话管理，源码分析页 V1 不持久化
        } else if (event === 'message') {
          acc += data
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = { ...next[next.length - 1], content: acc }
            return next
          })
        } else if (event === 'reasoning') {
          reasoningAcc += data
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = { ...next[next.length - 1], reasoning: reasoningAcc }
            return next
          })
        } else if (event === 'tool_call') {
          try {
            const parsed = JSON.parse(data)
            traceAcc = [...traceAcc, { ...parsed, type: 'tool_call' }]
            setMessages((prev) => {
              const next = [...prev]
              next[next.length - 1] = { ...next[next.length - 1], trace: [...traceAcc] }
              return next
            })
          } catch { /* ignore */ }
        } else if (event === 'tool_result') {
          try {
            const parsed = JSON.parse(data)
            traceAcc = [...traceAcc, { ...parsed, type: 'tool_result' }]
            setMessages((prev) => {
              const next = [...prev]
              next[next.length - 1] = { ...next[next.length - 1], trace: [...traceAcc] }
              return next
            })
          } catch { /* ignore */ }
        } else if (event === 'error') {
          let msg = data
          try { msg = JSON.parse(data).error || data } catch { /* keep raw */ }
          throw new Error(msg)
        } else if (event === 'done') {
          break
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        setError(`分析失败：${e.message || '请检查后端服务是否已启动'}`)
      }
    } finally {
      setStreaming(false)
      abortRef.current = null
    }
  }

  const stop = () => {
    abortRef.current?.abort()
    setStreaming(false)
  }

  const clearChat = () => {
    stop()
    setMessages([])
    setError('')
  }

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 156px)' }}>
      <div style={{ flex: 1, minWidth: 0, display: 'flex' }}>
        <Card
          title={
            <Space>
              <span className="clay-icon-box" style={{ width: 36, height: 36, fontSize: 18, background: CLAY.mintTint }}>
                <CodeOutlined style={{ color: CLAY.mint }} />
              </span>
              <span>源码分析</span>
              <Tag style={{ background: CLAY.mintTint, color: CLAY.mint }}>repo/ 沙箱</Tag>
            </Space>
          }
          extra={
            <Button
              type="text"
              icon={<ClearOutlined />}
              onClick={clearChat}
              disabled={!messages.length && !streaming}
            >
              清空对话
            </Button>
          }
          style={{ display: 'flex', flexDirection: 'column', height: '100%', width: '100%' }}
          styles={{
            body: {
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              padding: 20,
              overflow: 'hidden',
              minHeight: 0,
            },
          }}
        >
          <div
            style={{
              flex: 1,
              overflowY: 'auto',
              padding: '8px 4px',
            }}
          >
            {messages.length === 0 ? (
              <div style={{ textAlign: 'center', marginTop: 70 }}>
                <div
                  className="clay-icon-box clay-float"
                  style={{ width: 84, height: 84, fontSize: 44, background: CLAY.mintTint, margin: '0 auto 20px' }}
                >
                  <CodeOutlined style={{ color: CLAY.mint }} />
                </div>
                <Typography.Title level={4} style={{ fontWeight: 800, letterSpacing: '-0.02em' }}>
                  源码分析助手
                </Typography.Title>
                <Typography.Text type="secondary">
                  贴上报错或问 repo/ 下项目的代码问题，我来读源码帮你定位
                </Typography.Text>
                <Space direction="vertical" style={{ marginTop: 24 }} size={10}>
                  {suggestions.map((s) => (
                    <Button
                      key={s}
                      shape="round"
                      style={{ height: 42, fontWeight: 700, background: CLAY.mintTint, color: CLAY.mint, border: 'none', boxShadow: CLAY_SHADOW.small }}
                      onClick={() => send(s)}
                      disabled={streaming}
                    >
                      {s}
                    </Button>
                  ))}
                </Space>
              </div>
            ) : (
              <Space direction="vertical" style={{ width: '100%' }} size={20}>
                {messages.map((msg, i) =>
                  msg.role === 'user' ? (
                    <div key={i} style={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <div
                        style={{
                          maxWidth: '72%',
                          background: BRAND.primaryGradient,
                          color: '#fff',
                          borderRadius: '22px 22px 6px 22px',
                          padding: '12px 18px',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                          fontWeight: 600,
                          boxShadow: 'inset 0 -3px 6px rgba(255,255,255,0.25), inset 0 2px 4px rgba(0,0,0,0.05), 0 8px 18px rgba(108,92,231,0.35)',
                        }}
                      >
                        {msg.content}
                      </div>
                    </div>
                  ) : (
                    <div key={i} style={{ display: 'flex', gap: 12 }}>
                      <span
                        className="clay-icon-box"
                        style={{ width: 40, height: 40, fontSize: 20, background: CLAY.mintTint, flexShrink: 0 }}
                      >
                        <CodeOutlined style={{ color: CLAY.mint }} />
                      </span>
                      <div
                        style={{
                          maxWidth: '76%',
                          background: '#fff',
                          borderRadius: '22px 22px 22px 6px',
                          padding: '12px 18px',
                          minWidth: 60,
                          boxShadow: CLAY_SHADOW.raised,
                        }}
                      >
                        {msg.reasoning && (
                          <details
                            style={{
                              marginBottom: 8,
                              border: 'none',
                              background: 'rgba(99,102,241,0.06)',
                              borderRadius: 12,
                              padding: '8px 12px',
                              fontSize: 13,
                              color: CLAY.inkSoft,
                            }}
                          >
                            <summary style={{ cursor: 'pointer', fontWeight: 700, userSelect: 'none' }}>
                              思考过程
                            </summary>
                            <div style={{ marginTop: 6, whiteSpace: 'pre-wrap', opacity: 0.85 }}>
                              {msg.reasoning}
                            </div>
                          </details>
                        )}
                        {msg.trace && msg.trace.length > 0 && (
                          <details
                            style={{
                              marginBottom: 8,
                              border: 'none',
                              background: 'rgba(99,102,241,0.04)',
                              borderRadius: 12,
                              padding: '8px 12px',
                              fontSize: 13,
                              color: CLAY.inkSoft,
                            }}
                          >
                            <summary style={{ cursor: 'pointer', fontWeight: 700, userSelect: 'none' }}>
                              🔧 推理路径（{msg.trace.length} 步）
                            </summary>
                            <div style={{ marginTop: 6 }}>
                              {msg.trace.map((t, ti) => (
                                <div key={ti} style={{
                                  marginBottom: 6,
                                  padding: '6px 10px',
                                  background: t.type === 'tool_result' && t.summary && (t.summary.includes('denied') || t.summary.includes('error') || t.summary.includes('Error'))
                                    ? 'rgba(244,67,54,0.08)' : 'rgba(0,0,0,0.03)',
                                  borderRadius: 8,
                                  borderLeft: t.type === 'tool_call' ? '3px solid #6c5ce7' : '3px solid #00b894',
                                }}>
                                  <span style={{ fontWeight: 700, color: t.type === 'tool_call' ? '#6c5ce7' : '#00b894' }}>
                                    {t.type === 'tool_call' ? '🔍' : '↳'} Step {t.step}: {t.tool}
                                  </span>
                                  {t.type === 'tool_call' && t.args && (
                                    <div style={{ marginTop: 2, fontSize: 12, fontFamily: 'monospace', opacity: 0.7, wordBreak: 'break-all' }}>
                                      args: {typeof t.args === 'string' ? t.args : JSON.stringify(t.args)}
                                    </div>
                                  )}
                                  {t.type === 'tool_result' && (
                                    <>
                                      <div style={{ marginTop: 2, fontSize: 12, opacity: 0.7, wordBreak: 'break-all' }}>
                                        {t.summary}
                                      </div>
                                      {t.truncated && (
                                        <span style={{ fontSize: 11, color: '#e6a700', fontWeight: 700 }}> ⚠ 截断</span>
                                      )}
                                    </>
                                  )}
                                </div>
                              ))}
                            </div>
                          </details>
                        )}
                        {msg.content ? (
                          <div className="chat-markdown">
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                              {normalizeMarkdown(msg.content)}
                            </ReactMarkdown>
                          </div>
                        ) : (
                          <Spin indicator={<LoadingOutlined spin />} size="small" />
                        )}
                      </div>
                    </div>
                  ),
                )}
              </Space>
            )}
            <div ref={bottomRef} />
          </div>

          {error && (
            <Alert
              type="error"
              showIcon
              message={error}
              closable
              style={{ marginBottom: 12, borderRadius: 20 }}
              onClose={() => setError('')}
            />
          )}

          <div
            className="clay-inset"
            style={{ display: 'flex', alignItems: 'flex-end', gap: 10, padding: 10, marginTop: 12 }}
          >
            <TextArea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onPressEnter={(e) => {
                if (!e.shiftKey) {
                  e.preventDefault()
                  send()
                }
              }}
              placeholder="输入问题，如：Nacos 的服务注册逻辑在哪？"
              autoSize={{ minRows: 1, maxRows: 4 }}
              disabled={streaming}
              variant="borderless"
              style={{ background: 'transparent', padding: '8px 10px', fontWeight: 600 }}
            />
            {streaming ? (
              <Button danger icon={<StopOutlined />} style={{ height: 42 }} onClick={stop}>
                停止
              </Button>
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={() => send()}
                disabled={!input.trim()}
                style={{ height: 42, fontWeight: 800 }}
              >
                发送
              </Button>
            )}
          </div>
        </Card>
      </div>
    </div>
  )
}
