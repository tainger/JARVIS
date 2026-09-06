import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import {
  SearchOutlined,
  EyeOutlined,
  ToolOutlined,
  BugOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { conversationApi, traceApi } from '../api/client'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'

const { TextArea } = Input
const { Title, Text } = Typography

const stepIcons = {
  reasoning: '💭',
  tool_call: '🔍',
  tool_result: '↳',
  summary: '📝',
}

const stepColors = {
  reasoning: '#6c5ce7',
  tool_call: '#0984e3',
  tool_result: '#00b894',
  summary: '#e17055',
}

function isErrSummary(summary) {
  if (!summary) return false
  const lower = summary.toLowerCase()
  return lower.includes('denied') || lower.includes('error') || lower.includes('exception') || lower.includes('failed')
}

export default function AgentTraces() {
  const [conversations, setConversations] = useState([])
  const [selectedConv, setSelectedConv] = useState(null)
  const [messages, setMessages] = useState([])
  const [traces, setTraces] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const loadConversations = useCallback(async () => {
    try {
      const list = await conversationApi.list()
      setConversations(list || [])
    } catch {
      // silent
    }
  }, [])

  useEffect(() => {
    loadConversations()
  }, [loadConversations])

  const loadTraces = async (convId) => {
    if (!convId) return
    setSelectedConv(convId)
    setLoading(true)
    setError('')
    setMessages([])
    setTraces([])
    try {
      const [convData, traceData] = await Promise.all([
        conversationApi.get(convId, 0, 100),
        traceApi.list(convId),
      ])
      const msgs = (convData.messages || []).filter((m) => m.role === 'user' || m.role === 'assistant')
      setMessages(msgs)
      setTraces(traceData || [])
    } catch (e) {
      setError(`加载失败：${e.message || '未知错误'}`)
    } finally {
      setLoading(false)
    }
  }

  // 按 messageId 分组 trace
  const traceMap = {}
  for (const t of traces) {
    if (!traceMap[t.messageId]) traceMap[t.messageId] = []
    traceMap[t.messageId].push(t)
  }

  // 精确关联：用消息 id 直接匹配 traceMap
  const enriched = messages.map((m) => ({
    ...m,
    traceSteps: m.role === 'assistant' ? (traceMap[m.id] || []) : [],
  }))

  // 统计
  const totalSteps = traces.length
  const toolCallCount = traces.filter((t) => t.stepType === 'tool_call').length
  const errorCount = traces.filter((t) => t.stepType === 'tool_result' && isErrSummary(t.toolResult)).length
  const truncatedCount = traces.filter((t) => t.isTruncated).length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card
        title={
          <Space>
            <span className="clay-icon-box" style={{ width: 36, height: 36, fontSize: 18, background: CLAY.coralTint }}>
              <BugOutlined style={{ color: CLAY.coral }} />
            </span>
            <span>推理轨迹诊断</span>
            <Tag style={{ background: CLAY.coralTint, color: CLAY.coral }}>可观测性</Tag>
          </Space>
        }
        style={{ borderRadius: 20, boxShadow: CLAY_SHADOW.raised }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <div>
            <Text type="secondary" style={{ fontWeight: 700, marginRight: 8 }}>选择会话：</Text>
            <Select
              style={{ width: 360 }}
              placeholder="选择一个会话查看推理轨迹"
              value={selectedConv}
              onChange={loadTraces}
              showSearch
              optionFilterProp="label"
              options={conversations.map((c) => ({ value: c.id, label: `${c.title} (#${c.id})` }))}
            />
          </div>
          {selectedConv && (
            <Space size={12}>
              <Tag style={{ background: CLAY.purpleTint, color: CLAY.purple, fontWeight: 700 }}>
                共 {totalSteps} 步
              </Tag>
              <Tag style={{ background: CLAY.mintTint, color: CLAY.mint, fontWeight: 700 }}>
                <ToolOutlined /> {toolCallCount} 次工具调用
              </Tag>
              {errorCount > 0 && (
                <Tag color="red" style={{ fontWeight: 700 }}>{errorCount} 次错误</Tag>
              )}
              {truncatedCount > 0 && (
                <Tag color="orange" style={{ fontWeight: 700 }}>{truncatedCount} 次截断</Tag>
              )}
            </Space>
          )}
        </Space>
      </Card>

      {error && (
        <Alert type="error" showIcon message={error} closable onClose={() => setError('')} style={{ borderRadius: 20 }} />
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin size="large" />
        </div>
      )}

      {!loading && !selectedConv && (
        <Card style={{ borderRadius: 20, boxShadow: CLAY_SHADOW.small }}>
          <Empty description="选择一个会话开始诊断 Agent 推理路径" />
        </Card>
      )}

      {!loading && selectedConv && enriched.length === 0 && (
        <Card style={{ borderRadius: 20, boxShadow: CLAY_SHADOW.small }}>
          <Empty description="该会话暂无消息记录" />
        </Card>
      )}

      {!loading && selectedConv && enriched.length > 0 && (
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          {enriched.map((msg, i) => (
            <Card
              key={i}
              size="small"
              style={{
                borderRadius: 20,
                boxShadow: CLAY_SHADOW.small,
                border: msg.role === 'user'
                  ? `2px solid ${BRAND.primary}30`
                  : msg.traceSteps.length > 0
                    ? `2px solid ${CLAY.mint}40`
                    : '1px solid transparent',
              }}
            >
              {/* 消息头部 */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <Tag style={{
                  background: msg.role === 'user' ? BRAND.primaryGradient : CLAY.mintTint,
                  color: msg.role === 'user' ? '#fff' : CLAY.mint,
                  fontWeight: 800,
                  border: 'none',
                }}>
                  {msg.role === 'user' ? '👤 用户' : '🤖 Agent'}
                </Tag>
                {msg.traceSteps.length > 0 && (
                  <Tag style={{ background: CLAY.coralTint, color: CLAY.coral, fontWeight: 700, border: 'none' }}>
                    {msg.traceSteps.length} 步推理
                  </Tag>
                )}
              </div>

              {/* 用户消息 */}
              {msg.role === 'user' && (
                <div style={{ padding: '8px 12px', background: 'rgba(108,92,231,0.04)', borderRadius: 12, fontWeight: 600 }}>
                  {msg.content}
                </div>
              )}

              {/* Agent 消息 + 推理轨迹 */}
              {msg.role === 'assistant' && (
                <Space direction="vertical" style={{ width: '100%' }} size={10}>
                  {msg.traceSteps.length > 0 && (
                    <div style={{
                      background: 'rgba(99,102,241,0.03)',
                      borderRadius: 12,
                      padding: '12px 16px',
                      border: '1px dashed rgba(99,102,241,0.15)',
                    }}>
                      <div style={{ fontSize: 13, fontWeight: 800, color: CLAY.inkSoft, marginBottom: 10 }}>
                        🔧 推理路径
                      </div>
                      <Timeline
                        items={msg.traceSteps.map((t) => {
                          const color = stepColors[t.stepType] || '#999'
                          const isError = t.stepType === 'tool_result' && isErrSummary(t.toolResult)
                          return {
                            color: isError ? 'red' : color,
                            children: (
                              <div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                                  <span style={{ fontWeight: 700, color: isError ? '#f44336' : color }}>
                                    {stepIcons[t.stepType] || '•'} Step {t.stepIndex}
                                  </span>
                                  <Tag style={{
                                    background: 'transparent',
                                    border: `1px solid ${color}40`,
                                    color: color,
                                    fontSize: 11,
                                    fontWeight: 700,
                                  }}>
                                    {t.stepType}
                                  </Tag>
                                  {t.toolName && (
                                    <Tag style={{
                                      background: CLAY.purpleTint,
                                      color: CLAY.purple,
                                      fontWeight: 700,
                                      border: 'none',
                                    }}>
                                      {t.toolName}
                                    </Tag>
                                  )}
                                  {t.isTruncated && (
                                    <Tag color="orange" style={{ fontWeight: 700 }}>⚠ 截断</Tag>
                                  )}
                                </div>
                                {t.content && (
                                  <div style={{
                                    marginTop: 4,
                                    fontSize: 13,
                                    color: CLAY.inkSoft,
                                    whiteSpace: 'pre-wrap',
                                    opacity: 0.85,
                                  }}>
                                    {t.content.length > 300 ? t.content.substring(0, 300) + '…' : t.content}
                                  </div>
                                )}
                                {t.toolArgs && (
                                  <div style={{
                                    marginTop: 4,
                                    fontSize: 12,
                                    fontFamily: 'monospace',
                                    background: 'rgba(0,0,0,0.03)',
                                    padding: '4px 8px',
                                    borderRadius: 6,
                                    wordBreak: 'break-all',
                                  }}>
                                    args: {t.toolArgs.length > 200 ? t.toolArgs.substring(0, 200) + '…' : t.toolArgs}
                                  </div>
                                )}
                                {t.toolResult && (
                                  <div style={{
                                    marginTop: 4,
                                    fontSize: 12,
                                    padding: '4px 8px',
                                    borderRadius: 6,
                                    background: isError ? 'rgba(244,67,54,0.06)' : 'rgba(0,0,0,0.03)',
                                    color: isError ? '#d32f2f' : CLAY.inkSoft,
                                    wordBreak: 'break-all',
                                  }}>
                                    {t.toolResult.length > 300 ? t.toolResult.substring(0, 300) + '…' : t.toolResult}
                                  </div>
                                )}
                              </div>
                            ),
                          }
                        })}
                      />
                    </div>
                  )}
                  {/* Agent 回答 */}
                  {msg.content ? (
                    <div className="chat-markdown" style={{ padding: '4px 8px' }}>
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {msg.content}
                      </ReactMarkdown>
                    </div>
                  ) : (
                    <Text type="secondary" italic>（无回复内容）</Text>
                  )}
                </Space>
              )}
            </Card>
          ))}
        </Space>
      )}
    </div>
  )
}
