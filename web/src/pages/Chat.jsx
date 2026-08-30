import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Drawer, Input, Modal, Space, Spin, Tag, Typography, message } from 'antd'
import { ClearOutlined, FileTextOutlined, LoadingOutlined, SendOutlined, StopOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { evalApi, knowledgeApi, streamChat } from '../api/client'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'

const { TextArea } = Input

/**
 * 归一化模型输出的 Markdown：模型偶尔输出 "###标题"、"1.条目" 这种贴身写法，
 * CommonMark 要求 #/数字后有空格才认作标题/列表项，这里统一补上空格。
 */
function normalizeMarkdown(text) {
  return text
    .replace(/^(#{1,6})(?=[^#\s])/gm, '$1 ')
    .replace(/^(\s*)(\d+)\.(?=\S)/gm, '$1$2. ')
}

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
  const [viewingDoc, setViewingDoc] = useState(null) // { id, title }
  const [docContent, setDocContent] = useState('')
  const [docLoading, setDocLoading] = useState(false)
  const [dislike, setDislike] = useState(null) // { question, answer } 👎 提交候选池
  const [dislikeNote, setDislikeNote] = useState('')
  const [dislikeSubmitting, setDislikeSubmitting] = useState(false)
  const abortRef = useRef(null)
  const bottomRef = useRef(null)
  const listRef = useRef(null)

  const openSourceDoc = async (source) => {
    setViewingDoc({ id: source.documentId, title: source.documentTitle })
    setDocContent('')
    setDocLoading(true)
    try {
      const doc = await knowledgeApi.get(source.documentId)
      setDocContent(doc.content || '（文档内容为空）')
    } catch (e) {
      setDocContent('加载文档失败：' + (e.message || '未知错误'))
    } finally {
      setDocLoading(false)
    }
  }

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
    const botMsg = { role: 'assistant', content: '', sources: [] }
    setMessages((prev) => [...prev, userMsg, botMsg])
    setStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      let acc = ''
      for await (const { event, data } of streamChat(content, controller.signal)) {
        if (event === 'message') {
          acc += data
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = { ...next[next.length - 1], content: acc }
            return next
          })
        } else if (event === 'sources') {
          let parsed = []
          try {
            parsed = JSON.parse(data)
          } catch {
            parsed = []
          }
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = { ...next[next.length - 1], sources: parsed }
            return next
          })
        } else if (event === 'error') {
          let msg = data
          try {
            msg = JSON.parse(data).error || data
          } catch {
            // 保留原始文本
          }
          throw new Error(msg)
        }
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

  /** 👎 提交候选池：问题 + 备注 + 回答摘要，评测中心 triage 后转正进标注集 */
  const submitDislike = async () => {
    if (!dislike) return
    setDislikeSubmitting(true)
    try {
      await evalApi.submitCandidate({
        question: dislike.question,
        note: dislikeNote.trim() || null,
        source: 'chat',
        chatRef: dislike.answer.slice(0, 200),
      })
      message.success('已提交候选池，可在评测中心转正为评测用例')
      setDislike(null)
      setDislikeNote('')
    } catch (e) {
      // 409（重复）等信息已由拦截器弹出
    } finally {
      setDislikeSubmitting(false)
    }
  }

  return (
    <Card
      title={
        <Space>
          <span className="clay-icon-box" style={{ width: 36, height: 36, fontSize: 18, background: CLAY.purpleTint }}>
            🤖
          </span>
          <span>JARVIS AI 助手</span>
          <Tag style={{ background: CLAY.mintTint, color: CLAY.mint }}>SSE 流式</Tag>
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
      style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 156px)' }}
      styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', padding: 20 } }}
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
          <div style={{ textAlign: 'center', marginTop: 70 }}>
            <div
              className="clay-icon-box clay-float"
              style={{ width: 84, height: 84, fontSize: 44, background: CLAY.purpleTint, margin: '0 auto 20px' }}
            >
              🤖
            </div>
            <Typography.Title level={4} style={{ fontWeight: 800, letterSpacing: '-0.02em' }}>
              开始与 JARVIS 对话
            </Typography.Title>
            <Typography.Text type="secondary">支持任务库工具调用与知识库检索</Typography.Text>
            <Space direction="vertical" style={{ marginTop: 24 }} size={10}>
              {suggestions.map((s) => (
                <Button
                  key={s}
                  shape="round"
                  style={{ height: 42, fontWeight: 700, background: CLAY.purpleTint, color: BRAND.primary, border: 'none', boxShadow: CLAY_SHADOW.small }}
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
                    style={{ width: 40, height: 40, fontSize: 20, background: CLAY.purpleTint, flexShrink: 0 }}
                  >
                    🤖
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
                    {msg.content ? (
                      <div className="chat-markdown">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {normalizeMarkdown(msg.content)}
                        </ReactMarkdown>
                      </div>
                    ) : (
                      <Spin indicator={<LoadingOutlined spin />} size="small" />
                    )}
                    <SourceList sources={msg.sources} onOpenDoc={openSourceDoc} />
                    {msg.content && !(streaming && i === messages.length - 1) && (
                      <div style={{ marginTop: 6, textAlign: 'right' }}>
                        <Button
                          type="text"
                          size="small"
                          onClick={() =>
                            setDislike({ question: messages[i - 1]?.content || '', answer: msg.content })
                          }
                          style={{ color: CLAY.inkSoft, fontWeight: 700, fontSize: 12 }}
                        >
                          👎 回答不满意
                        </Button>
                      </div>
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
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={streaming}
          variant="borderless"
          style={{ background: 'transparent', padding: '8px 10px', fontWeight: 600 }}
        />
        {streaming ? (
          <Button danger icon={<StopOutlined />} style={{ height: 42 }}>
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

      <Modal
        title="👎 提交到评测候选池"
        open={!!dislike}
        onOk={submitDislike}
        confirmLoading={dislikeSubmitting}
        onCancel={() => {
          setDislike(null)
          setDislikeNote('')
        }}
        okText="提交"
      >
        {dislike && (
          <>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
              问题：{dislike.question}
            </Typography.Paragraph>
            <TextArea
              value={dislikeNote}
              onChange={(e) => setDislikeNote(e.target.value)}
              placeholder="哪里不满意？（如：答案错误 / 引用不对 / 没查到知识库）"
              autoSize={{ minRows: 2, maxRows: 4 }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              提交后进入评测中心候选池，triage 转正后成为评测标注用例。
            </Typography.Text>
          </>
        )}
      </Modal>

      <Drawer
        title={
          <Space>
            <span className="clay-icon-box" style={{ width: 32, height: 32, fontSize: 16, background: CLAY.purpleTint }}>
              📄
            </span>
            <span style={{ fontWeight: 800 }}>{viewingDoc?.title}</span>
          </Space>
        }
        placement="right"
        width={640}
        open={!!viewingDoc}
        onClose={() => setViewingDoc(null)}
        styles={{ body: { padding: 20 } }}
      >
        {docLoading ? (
          <div style={{ textAlign: 'center', marginTop: 60 }}>
            <Spin indicator={<LoadingOutlined spin />} />
          </div>
        ) : (
          <div className="chat-markdown">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{docContent}</ReactMarkdown>
          </div>
        )}
      </Drawer>
    </Card>
  )
}

/**
 * AI 回答下方的知识库来源卡片：与回答中的 [n] 编号一一对应，
 * 点击卡片展开片段摘要；点"查看原文"弹出完整文档抽屉回溯。
 */
function SourceList({ sources, onOpenDoc }) {
  const [openRef, setOpenRef] = useState(null)
  if (!sources || sources.length === 0) return null
  return (
    <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px dashed rgba(108,92,231,0.25)' }}>
      <div style={{ fontSize: 12, fontWeight: 800, color: BRAND.primary, marginBottom: 6 }}>
        📚 知识库来源
      </div>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        {sources.map((s) => (
          <div
            key={s.ref}
            onClick={() => setOpenRef(openRef === s.ref ? null : s.ref)}
            style={{
              cursor: 'pointer',
              background: CLAY.mintTint,
              borderRadius: 16,
              padding: '6px 12px',
              fontSize: 12,
              fontWeight: 700,
              lineHeight: 1.5,
            }}
          >
            <Space size={6} wrap style={{ width: '100%', justifyContent: 'space-between' }}>
              <Space size={6} wrap>
                <span style={{ fontWeight: 800 }}>
                  [{s.ref}] {s.documentTitle}
                </span>
                <span style={{ color: 'rgba(0,0,0,0.45)' }}>片段 {s.seq}</span>
                <Tag style={{ background: '#fff', color: CLAY.mint, fontWeight: 800, borderRadius: 999 }}>
                  {(s.score * 100).toFixed(0)}%
                </Tag>
              </Space>
              <Button
                type="link"
                size="small"
                icon={<FileTextOutlined />}
                onClick={(e) => {
                  e.stopPropagation()
                  onOpenDoc?.(s)
                }}
                style={{ padding: 0, height: 22, fontWeight: 700 }}
              >
                查看原文
              </Button>
            </Space>
            {openRef === s.ref && (
              <div
                style={{
                  marginTop: 6,
                  padding: '8px 10px',
                  background: '#fff',
                  borderRadius: 12,
                  fontWeight: 500,
                  color: 'rgba(0,0,0,0.72)',
                }}
              >
                {s.snippet}
              </div>
            )}
          </div>
        ))}
      </Space>
    </div>
  )
}
