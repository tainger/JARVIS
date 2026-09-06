import { useEffect, useState, useCallback } from 'react'
import {
  Badge,
  Button,
  Card,
  Input,
  Select,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd'
import {
  BugOutlined,
  CheckCircleOutlined,
  DiffOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import PageContainer from '../components/PageContainer'
import { conversationApi, debugApi } from '../api/client'
import { BRAND, CLAY, RADIUS } from '../theme'

const { Text, Paragraph } = Typography
const { TextArea } = Input

const STEP_ICONS = {
  reasoning: '💭',
  tool_call: '🔍',
  tool_result: '↳',
  summary: '📝',
}

const STEP_COLORS = {
  reasoning: '#6c5ce7',
  tool_call: '#0984e3',
  tool_result: '#00b894',
  summary: '#e17055',
}

export default function AgentDebugger() {
  const [conversations, setConversations] = useState([])
  const [selectedConv, setSelectedConv] = useState(null)
  const [stepsData, setStepsData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [selectedStep, setSelectedStep] = useState(null)
  const [context, setContext] = useState(null)
  const [contextLoading, setContextLoading] = useState(false)
  const [breakpoints, setBreakpoints] = useState(new Set())
  const [bpNote, setBpNote] = useState('')
  const [diffConvA, setDiffConvA] = useState(null)
  const [diffConvB, setDiffConvB] = useState(null)
  const [diffData, setDiffData] = useState(null)
  const [diffLoading, setDiffLoading] = useState(false)

  const loadConversations = useCallback(async () => {
    try {
      const list = await conversationApi.list()
      setConversations(list || [])
    } catch (e) {
      // 静默失败
    }
  }, [])

  useEffect(() => {
    loadConversations()
  }, [loadConversations])

  const loadSteps = async (convId) => {
    setSelectedConv(convId)
    setLoading(true)
    setSelectedStep(null)
    setContext(null)
    try {
      const data = await debugApi.steps(convId)
      setStepsData(data)
      const bps = await debugApi.breakpoints(convId)
      const bpSet = new Set(
        (bps || []).map((b) => `${b.messageId}-${b.stepIndex}`)
      )
      setBreakpoints(bpSet)
    } catch (e) {
      message.error('加载步骤失败：' + (e.message || '未知错误'))
    } finally {
      setLoading(false)
    }
  }

  const loadContext = async (messageId, stepIndex) => {
    setSelectedStep({ messageId, stepIndex })
    setContextLoading(true)
    try {
      const ctx = await debugApi.context(selectedConv, messageId, stepIndex)
      setContext(ctx)
    } catch (e) {
      message.error('加载上下文失败：' + (e.message || '未知错误'))
    } finally {
      setContextLoading(false)
    }
  }

  const toggleBreakpoint = async (messageId, stepIndex) => {
    const key = `${messageId}-${stepIndex}`
    const has = breakpoints.has(key)
    try {
      await debugApi.toggleBreakpoint({
        conversationId: selectedConv,
        messageId,
        stepIndex,
        action: has ? 'remove' : 'add',
        note: bpNote || null,
      })
      const newBp = new Set(breakpoints)
      if (has) {
        newBp.delete(key)
      } else {
        newBp.add(key)
      }
      setBreakpoints(newBp)
      setBpNote('')
      message.success(has ? '断点已移除' : '断点已添加')
    } catch (e) {
      message.error('操作失败：' + (e.message || '未知错误'))
    }
  }

  const runDiff = async () => {
    if (!diffConvA || !diffConvB) {
      message.warning('请选择两个对话')
      return
    }
    setDiffLoading(true)
    try {
      const data = await debugApi.diff(diffConvA, diffConvB)
      setDiffData(data)
    } catch (e) {
      message.error('路径对比失败：' + (e.message || '未知错误'))
    } finally {
      setDiffLoading(false)
    }
  }

  return (
    <PageContainer
      title="推理调试器"
      emoji="🐛"
      breadcrumb={[{ title: '首页', to: '/' }, { title: '推理调试' }]}
      description="IDE 风格的 Agent 推理调试 — 步骤导航、断点检查、上下文重建、路径对比"
      extra={
        <ReloadOutlined onClick={loadConversations} style={{ fontSize: 18, cursor: 'pointer', color: CLAY.inkSoft }} />
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: '240px 1fr', gap: 16 }}>
        {/* 左栏：会话选择器 */}
        <Card
          title="选择对话"
          size="small"
          style={{ borderRadius: RADIUS.lg, height: 'fit-content' }}
        >
          <Select
            style={{ width: '100%' }}
            placeholder="选择对话"
            value={selectedConv}
            onChange={loadSteps}
            options={conversations.map((c) => ({
              value: c.id,
              label: c.title || `对话 ${c.id}`,
            }))}
          />
        </Card>

        {/* 右栏：调试面板 */}
        <div>
          {loading ? (
            <Spin size="large" style={{ display: 'block', padding: 60 }} />
          ) : !stepsData ? (
            <Card style={{ borderRadius: RADIUS.lg, textAlign: 'center', padding: 40 }}>
              <Text type="secondary" style={{ fontSize: 15 }}>
                选择一个对话开始调试
              </Text>
            </Card>
          ) : (
            <>
              {/* 步骤导航栏 */}
              <Card
                title={
                  <Space>
                    <BugOutlined style={{ color: BRAND.primary }} />
                    推理步骤 — {stepsData.conversation?.title || ''}
                  </Space>
                }
                size="small"
                style={{ borderRadius: RADIUS.lg, marginBottom: 16 }}
              >
                {(!stepsData.messages || stepsData.messages.length === 0) ? (
                  <Text type="secondary">该对话没有推理轨迹数据</Text>
                ) : (
                  stepsData.messages.map((msg) => (
                    <div key={msg.messageId} style={{ marginBottom: 16 }}>
                      <div style={{ marginBottom: 8, color: CLAY.inkSoft, fontSize: 13 }}>
                        消息 #{msg.messageId} — {msg.stepCount} 步
                      </div>
                      <Timeline
                        items={msg.steps.map((step) => {
                          const bpKey = `${msg.messageId}-${step.stepIndex}`
                          const hasBp = breakpoints.has(bpKey)
                          const color = STEP_COLORS[step.stepType] || '#999'
                          const icon = STEP_ICONS[step.stepType] || '•'
                          const isSelected = selectedStep?.messageId === msg.messageId
                            && selectedStep?.stepIndex === step.stepIndex
                          return {
                            color,
                            children: (
                              <div
                                style={{
                                  cursor: 'pointer',
                                  padding: '4px 8px',
                                  borderRadius: 6,
                                  background: isSelected ? CLAY.purpleTint : 'transparent',
                                  border: isSelected ? `1px solid ${BRAND.primary}` : '1px solid transparent',
                                }}
                                onClick={() => loadContext(msg.messageId, step.stepIndex)}
                              >
                                <Space>
                                  <span>{icon}</span>
                                  <Tag color={color} style={{ color: '#fff', fontSize: 12 }}>
                                    Step {step.stepIndex}
                                  </Tag>
                                  {step.toolName && (
                                    <Text strong style={{ fontSize: 13 }}>{step.toolName}</Text>
                                  )}
                                  {step.content && (
                                    <Text type="secondary" ellipsis style={{ fontSize: 12, maxWidth: 400 }}>
                                      {step.content.substring(0, 80)}
                                      {step.content.length > 80 ? '…' : ''}
                                    </Text>
                                  )}
                                  {hasBp && (
                                    <Badge color="red" text="🔴 断点" />
                                  )}
                                  {step.durationMs != null && (
                                    <Tag style={{ fontSize: 11 }}>{step.durationMs}ms</Tag>
                                  )}
                                </Space>
                              </div>
                            ),
                          }
                        })}
                      />
                    </div>
                  ))
                )}
              </Card>

              {/* 上下文检查面板 */}
              {selectedStep && (
                <Card
                  title={
                    <Space>
                      <ThunderboltOutlined style={{ color: BRAND.primary }} />
                      上下文检查 — Step {selectedStep.stepIndex}
                    </Space>
                  }
                  size="small"
                  style={{ borderRadius: RADIUS.lg, marginBottom: 16 }}
                  extra={
                    (() => {
                      const bpKey = `${selectedStep.messageId}-${selectedStep.stepIndex}`
                      const hasBp = breakpoints.has(bpKey)
                      return (
                        <Space>
                          {!hasBp && (
                            <Input
                              size="small"
                              placeholder="断点备注"
                              value={bpNote}
                              onChange={(e) => setBpNote(e.target.value)}
                              style={{ width: 150 }}
                            />
                          )}
                          <Button
                            size="small"
                            type={hasBp ? 'default' : 'primary'}
                            danger={hasBp}
                            onClick={() => toggleBreakpoint(selectedStep.messageId, selectedStep.stepIndex)}
                          >
                            {hasBp ? '移除断点' : '添加断点'}
                          </Button>
                        </Space>
                      )
                    })()
                  }
                >
                  {contextLoading ? (
                    <Spin size="large" style={{ display: 'block', padding: 40 }} />
                  ) : context ? (
                    <div>
                      {/* System Prompt */}
                      <div style={{ marginBottom: 12 }}>
                        <Text strong style={{ color: STEP_COLORS.reasoning }}>System Prompt:</Text>
                        <pre style={{
                          background: CLAY.purpleTint,
                          padding: 12,
                          borderRadius: 8,
                          fontSize: 12,
                          overflowX: 'auto',
                          maxHeight: 100,
                          overflowY: 'auto',
                          margin: '4px 0',
                        }}>
                          {context.systemPrompt || '(空)'}
                        </pre>
                      </div>

                      {/* 历史消息 */}
                      {context.priorMessages?.length > 0 && (
                        <div style={{ marginBottom: 12 }}>
                          <Text strong style={{ color: STEP_COLORS.tool_call }}>
                            历史消息 ({context.priorMessages.length}):
                          </Text>
                          {context.priorMessages.map((m, i) => (
                            <div key={i} style={{
                              padding: '6px 12px',
                              margin: '4px 0',
                              borderRadius: 6,
                              background: m.role === 'user' ? CLAY.coralTint : CLAY.purpleTint,
                              fontSize: 13,
                            }}>
                              <Tag>{m.role}</Tag>
                              <Text>{m.content}</Text>
                            </div>
                          ))}
                        </div>
                      )}

                      {/* 累积步骤 */}
                      {context.priorTraces?.length > 0 && (
                        <div style={{ marginBottom: 12 }}>
                          <Text strong style={{ color: STEP_COLORS.tool_result }}>
                            已执行步骤 ({context.priorTraces.length}):
                          </Text>
                          {context.priorTraces.map((t, i) => (
                            <div key={i} style={{
                              padding: '6px 12px',
                              margin: '4px 0',
                              borderRadius: 6,
                              background: CLAY.surface,
                              fontSize: 13,
                              borderLeft: `3px solid ${STEP_COLORS[t.stepType] || '#999'}`,
                            }}>
                              <Tag color={STEP_COLORS[t.stepType]}>
                                Step {t.stepIndex} {STEP_ICONS[t.stepType]}
                              </Tag>
                              {t.toolName && <Text strong> {t.toolName}</Text>}
                              {t.content && <div style={{ marginTop: 4, color: CLAY.inkSoft }}>{t.content}</div>}
                              {t.toolResult && (
                                <div style={{ marginTop: 4, fontSize: 12, color: CLAY.inkSoft }}>
                                  结果: {t.toolResult}
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      )}

                      {/* 当前步骤 */}
                      {context.currentStep && (
                        <div style={{
                          padding: 12,
                          borderRadius: 8,
                          background: CLAY.purpleTint,
                          border: `2px solid ${BRAND.primary}`,
                          marginBottom: 12,
                        }}>
                          <Tag color={BRAND.primary} style={{ fontSize: 14, padding: '2px 12px' }}>
                            当前 Step {context.currentStep.stepIndex}
                          </Tag>
                          {context.currentStep.toolName && (
                            <Text strong style={{ marginLeft: 8 }}>
                              {STEP_ICONS[context.currentStep.stepType]} {context.currentStep.toolName}
                            </Text>
                          )}
                          {context.currentStep.content && (
                            <Paragraph style={{ marginTop: 8 }}>
                              {context.currentStep.content}
                            </Paragraph>
                          )}
                          {context.currentStep.toolArgs && (
                            <pre style={{
                              fontSize: 12,
                              background: 'rgba(0,0,0,0.06)',
                              padding: 8,
                              borderRadius: 4,
                              margin: '4px 0',
                            }}>
                              参数: {context.currentStep.toolArgs}
                            </pre>
                          )}
                          {context.currentStep.toolResult && (
                            <pre style={{
                              fontSize: 12,
                              background: 'rgba(0,0,0,0.06)',
                              padding: 8,
                              borderRadius: 4,
                              margin: '4px 0',
                              maxHeight: 200,
                              overflowY: 'auto',
                            }}>
                              结果: {context.currentStep.toolResult}
                            </pre>
                          )}
                          {context.currentStep.durationMs != null && (
                            <Tag>耗时 {context.currentStep.durationMs}ms</Tag>
                          )}
                        </div>
                      )}

                      {/* 断点信息 */}
                      {context.breakpoint?.hasBreakpoint && (
                        <div style={{
                          padding: 8,
                          borderRadius: 6,
                          background: '#fff1f0',
                          border: '1px solid #ffa39e',
                        }}>
                          <Text style={{ color: '#cf1322' }}>
                            🔴 断点已标记{context.breakpoint.note ? `：${context.breakpoint.note}` : ''}
                          </Text>
                        </div>
                      )}
                    </div>
                  ) : (
                    <Text type="secondary">无法加载上下文</Text>
                  )}
                </Card>
              )}

              {/* 路径对比面板 */}
              <Card
                title={
                  <Space>
                    <DiffOutlined style={{ color: BRAND.primary }} />
                    路径对比
                  </Space>
                }
                size="small"
                style={{ borderRadius: RADIUS.lg }}
              >
                <Space style={{ marginBottom: 12 }}>
                  <Select
                    style={{ width: 200 }}
                    placeholder="对话 A"
                    value={diffConvA}
                    onChange={setDiffConvA}
                    options={conversations.map((c) => ({
                      value: c.id,
                      label: c.title || `对话 ${c.id}`,
                    }))}
                  />
                  <Select
                    style={{ width: 200 }}
                    placeholder="对话 B"
                    value={diffConvB}
                    onChange={setDiffConvB}
                    options={conversations.map((c) => ({
                      value: c.id,
                      label: c.title || `对话 ${c.id}`,
                    }))}
                  />
                  <Button
                    type="primary"
                    icon={<DiffOutlined />}
                    loading={diffLoading}
                    onClick={runDiff}
                  >
                    对比
                  </Button>
                </Space>

                {diffData && (
                  <div>
                    {diffData.forkStep > 0 ? (
                      <div style={{
                        padding: 8,
                        borderRadius: 6,
                        background: '#fff7e6',
                        border: '1px solid #ffd591',
                        marginBottom: 12,
                      }}>
                        <Text style={{ color: '#d46b08' }}>
                          🔀 分叉点：Step {diffData.forkStep}
                        </Text>
                      </div>
                    ) : (
                      <div style={{
                        padding: 8,
                        borderRadius: 6,
                        background: '#f6ffed',
                        border: '1px solid #b7eb8f',
                        marginBottom: 12,
                      }}>
                        <Text style={{ color: '#389e0d' }}>
                          <CheckCircleOutlined /> 两个对话的推理路径完全相同
                        </Text>
                      </div>
                    )}

                    <div style={{ overflowX: 'auto' }}>
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                        <thead>
                          <tr style={{ background: CLAY.surface }}>
                            <th style={{ padding: 8, textAlign: 'left', borderBottom: `2px solid ${CLAY.rule}` }}>Step</th>
                            <th style={{ padding: 8, textAlign: 'left', borderBottom: `2px solid ${CLAY.rule}` }}>对话 A</th>
                            <th style={{ padding: 8, textAlign: 'left', borderBottom: `2px solid ${CLAY.rule}` }}>对话 B</th>
                            <th style={{ padding: 8, textAlign: 'center', borderBottom: `2px solid ${CLAY.rule}` }}>匹配</th>
                          </tr>
                        </thead>
                        <tbody>
                          {diffData.steps?.map((s) => (
                            <tr
                              key={s.stepIndex}
                              style={{
                                background: s.isSame ? 'transparent' : s.stepIndex === diffData.forkStep ? '#fff1f0' : '#fffbe6',
                              }}
                            >
                              <td style={{ padding: 8, borderBottom: `1px solid ${CLAY.rule}` }}>{s.stepIndex}</td>
                              <td style={{ padding: 8, borderBottom: `1px solid ${CLAY.rule}` }}>
                                {s.typeA && <Tag color={STEP_COLORS[s.typeA]}>{s.typeA}</Tag>}
                                {s.toolA && <Text strong> {s.toolA}</Text>}
                                {s.argsA && <div style={{ fontSize: 11, color: CLAY.inkSoft }}>{s.argsA}</div>}
                                {s.contentA && <div style={{ fontSize: 11, color: CLAY.inkSoft }}>{s.contentA}</div>}
                              </td>
                              <td style={{ padding: 8, borderBottom: `1px solid ${CLAY.rule}` }}>
                                {s.typeB && <Tag color={STEP_COLORS[s.typeB]}>{s.typeB}</Tag>}
                                {s.toolB && <Text strong> {s.toolB}</Text>}
                                {s.argsB && <div style={{ fontSize: 11, color: CLAY.inkSoft }}>{s.argsB}</div>}
                                {s.contentB && <div style={{ fontSize: 11, color: CLAY.inkSoft }}>{s.contentB}</div>}
                              </td>
                              <td style={{ padding: 8, textAlign: 'center', borderBottom: `1px solid ${CLAY.rule}` }}>
                                {s.isSame ? '✅' : '❌'}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}
              </Card>
            </>
          )}
        </div>
      </div>
    </PageContainer>
  )
}
