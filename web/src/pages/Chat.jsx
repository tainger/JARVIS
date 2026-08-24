import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Avatar,
  Button,
  Card,
  Empty,
  Input,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import {
  ClearOutlined,
  LoadingOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import { streamChat } from '../api/client'

const { TextArea } = Input
const { Title, Text } = Typography

const suggestions = [
  '帮我列出所有任务',
  '创建一个新任务：准备周报',
  '查询任务库中有哪些已完成的任务',
]

export default function Chat() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState('')
  const abortRef = useRef(null)
  const bottomRef = useRef(null)
  const listRef = useRef(null)

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
    const botMsg = { role: 'assistant', content: '' }
    setMessages((prev) => [...prev, userMsg, botMsg])
    setStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      let acc = ''
      for await (const delta of streamChat(content, controller.signal)) {
        acc += delta
        setMessages((prev) => {
          const next = [...prev]
          next[next.length - 1] = { role: 'assistant', content: acc }
          return next
        })
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        setError(`对话失败：${e.message || '请检查后端服务是否已启动'}`)
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
    <Card
      title={
        <Space>
          <Avatar size="small" icon={<RobotOutlined />} style={{ background: '#2f54eb' }} />
          <span>JARVIS AI 助手</span>
          <Tag color="processing">SSE 流式</Tag>
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
      style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 140px)' }}
      styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', padding: 16 } }}
    >
      <div
        ref={listRef}
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '8px 4px',
        }}
      >
        {messages.length === 0 ? (
          <div style={{ textAlign: 'center', marginTop: 80 }}>
            <Empty description="开始与 JARVIS 对话，支持任务库工具调用" />
            <Space direction="vertical" style={{ marginTop: 16 }} size={8}>
              {suggestions.map((s) => (
                <Button
                  key={s}
                  type={suggestions.indexOf(s) === 0 ? 'primary' : 'default'}
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
                      background: '#2f54eb',
                      color: '#fff',
                      borderRadius: '12px 12px 2px 12px',
                      padding: '10px 14px',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                    }}
                  >
                    {msg.content}
                  </div>
                </div>
              ) : (
                <div key={i} style={{ display: 'flex', gap: 10 }}>
                  <Avatar
                    icon={<RobotOutlined />}
                    style={{ background: '#2f54eb', flexShrink: 0 }}
                  />
                  <div
                    style={{
                      maxWidth: '76%',
                      background: '#f6f7fb',
                      border: '1px solid #eef0f6',
                      borderRadius: '12px 12px 12px 2px',
                      padding: '10px 14px',
                      minWidth: 60,
                    }}
                  >
                    {msg.content ? (
                      <div className="chat-markdown">
                        <ReactMarkdown>{msg.content}</ReactMarkdown>
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
          style={{ marginBottom: 12 }}
          onClose={() => setError('')}
        />
      )}

      <Space.Compact style={{ width: '100%', marginTop: 12 }} block>
        <TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              send()
            }
          }}
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={streaming}
        />
        {streaming ? (
          <Button danger icon={<StopOutlined />} onClick={stop}>
            停止
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={() => send()}
            disabled={!input.trim()}
          >
            发送
          </Button>
        )}
      </Space.Compact>
    </Card>
  )
}
