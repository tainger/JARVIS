import { useCallback, useEffect, useState } from 'react'
import {
  Card,
  Col,
  Empty,
  Row,
  Segmented,
  Skeleton,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  BarChartOutlined,
  ReloadOutlined,
  ToolOutlined,
  BugOutlined,
} from '@ant-design/icons'
import { Bar, Pie, Line } from '@ant-design/charts'
import PageContainer from '../components/PageContainer'
import { analyticsApi } from '../api/client'
import { BRAND, CLAY, CLAY_SHADOW, RADIUS } from '../theme'

const { Text } = Typography

const statCards = [
  { key: 'totalConversations', emoji: '💬', label: '总会话数', tint: CLAY.purpleTint, color: BRAND.primary },
  { key: 'totalToolCalls', emoji: '🔧', label: '工具调用总数', tint: CLAY.mintTint, color: CLAY.mint },
  { key: 'avgStepsPerMessage', emoji: '📊', label: '平均 ReAct 步数', tint: CLAY.mustardTint, color: '#D4A017' },
  { key: 'hallucinationRate', emoji: '⚠️', label: '幻觉率 (%)', tint: CLAY.coralTint, color: CLAY.coral },
]

const skillColors = {
  'general-chat': CLAY.purple,
  'source-code-analysis': CLAY.mint,
  'knowledge-qa': CLAY.mustard,
  'task-management': CLAY.coral,
}

const skillLabels = {
  'general-chat': '通用对话',
  'source-code-analysis': '源码分析',
  'knowledge-qa': '知识问答',
  'task-management': '任务管理',
}

export default function ConversationAnalytics() {
  const [summary, setSummary] = useState(null)
  const [toolFreq, setToolFreq] = useState([])
  const [trend, setTrend] = useState([])
  const [skills, setSkills] = useState([])
  const [loading, setLoading] = useState(true)
  const [days, setDays] = useState(7)

  const load = useCallback(() => {
    setLoading(true)
    Promise.all([
      analyticsApi.summary(days),
      analyticsApi.toolFrequency(),
      analyticsApi.dailyTrend(days),
      analyticsApi.skillDistribution(),
    ])
      .then(([s, tf, tr, sd]) => {
        setSummary(s)
        setToolFreq(tf || [])
        setTrend((tr || []).map((d) => ({
          ...d,
          date: String(d.date),
        })))
        setSkills((sd || []).map((s) => ({
          ...s,
          label: skillLabels[s.skill] || s.skill,
        })))
      })
      .finally(() => setLoading(false))
  }, [days])

  useEffect(() => {
    load()
  }, [load])

  const hasData = summary && (summary.totalTraces > 0 || summary.totalConversations > 0)

  const toolColumns = [
    { title: '工具名', dataIndex: 'toolName', key: 'toolName', render: (v) => <Tag icon={<ToolOutlined />} color="purple">{v}</Tag> },
    { title: '调用次数', dataIndex: 'count', key: 'count', sorter: (a, b) => a.count - b.count, width: 100, align: 'center' },
    { title: '平均耗时 (ms)', dataIndex: 'avgDurationMs', key: 'avgDurationMs', sorter: (a, b) => a.avgDurationMs - b.avgDurationMs, width: 130, align: 'right' },
    { title: '最大耗时 (ms)', dataIndex: 'maxDurationMs', key: 'maxDurationMs', sorter: (a, b) => a.maxDurationMs - b.maxDurationMs, width: 130, align: 'right' },
    {
      title: '截断次数', dataIndex: 'truncatedCount', key: 'truncatedCount', width: 90, align: 'center',
      render: (v) => v > 0 ? <Tag color="orange">{v}</Tag> : <Text type="secondary">0</Text>,
    },
  ]

  return (
    <PageContainer
      title="对话分析"
      emoji="📊"
      breadcrumb={[{ title: '首页', to: '/' }, { title: '对话分析' }]}
      description="Agent 推理数据的聚合分析：工具调用频率、ReAct 步数趋势、技能使用分布和幻觉率指标。"
      extra={
        <Space>
          <Segmented
            value={String(days)}
            onChange={(v) => setDays(v === 'all' ? 0 : Number(v))}
            options={[
              { label: '7天', value: '7' },
              { label: '30天', value: '30' },
              { label: '全部', value: 'all' },
            ]}
          />
          <ReloadOutlined onClick={load} style={{ fontSize: 18, cursor: 'pointer', color: CLAY.inkSoft }} />
        </Space>
      }
    >
      {loading ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : !hasData ? (
        <Card style={{ borderRadius: RADIUS.lg, background: CLAY.base }}>
          <Empty
            description="暂无分析数据，开始对话后即可生成统计"
            style={{ padding: '60px 0' }}
          />
        </Card>
      ) : (
        <>
          {/* ── 统计卡片 ── */}
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            {statCards.map(({ key, emoji, label, tint, color }) => (
              <Col xs={12} sm={12} xl={6} key={key}>
                <Card
                  className="clay-card-hover"
                  style={{ background: tint, borderRadius: RADIUS.lg }}
                >
                  <Space size={12} align="center">
                    <span
                      className="clay-icon-box"
                      style={{ background: '#fff', boxShadow: CLAY_SHADOW.small }}
                    >
                      {emoji}
                    </span>
                    <Statistic
                      title={label}
                      value={summary[key] ?? 0}
                      suffix={key === 'hallucinationRate' ? '%' : ''}
                      valueStyle={{ color, fontWeight: 800 }}
                    />
                  </Space>
                </Card>
              </Col>
            ))}
          </Row>

          {/* ── 工具频率 + 技能分布 ── */}
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} xl={14}>
              <Card
                title={<Space><BarChartOutlined /> 工具调用频率</Space>}
                style={{ borderRadius: RADIUS.lg }}
                styles={{ body: { minHeight: 300 } }}
              >
                {toolFreq.length > 0 ? (
                  <Bar
                    data={toolFreq.map((t) => ({
                      tool: t.toolName,
                      count: t.count,
                    }))}
                    xField="count"
                    yField="tool"
                    colorField={BRAND.primary}
                    style={{ maxWidth: 40 }}
                    axis={{
                      x: { title: '调用次数' },
                      y: { title: null },
                    }}
                    label={{ text: 'count', position: 'right' }}
                    height={280}
                  />
                ) : (
                  <Empty description="无工具调用记录" style={{ padding: '40px 0' }} />
                )}
              </Card>
            </Col>
            <Col xs={24} xl={10}>
              <Card
                title={<Space><BugOutlined /> 技能使用分布</Space>}
                style={{ borderRadius: RADIUS.lg }}
                styles={{ body: { minHeight: 300 } }}
              >
                {skills.length > 0 ? (
                  <Pie
                    data={skills.map((s) => ({
                      type: s.label,
                      value: s.conversationCount,
                    }))}
                    angleField="value"
                    colorField="type"
                    innerRadius={0.5}
                    legend={{ color: { position: 'right' } }}
                    label={{
                      text: (d) => `${d.value} (${(d.percent * 100).toFixed(1)}%)`,
                      position: 'outside',
                    }}
                    height={280}
                    color={skills.map((s) => skillColors[s.skill] || CLAY.purple)}
                  />
                ) : (
                  <Empty description="无技能使用记录" style={{ padding: '40px 0' }} />
                )}
              </Card>
            </Col>
          </Row>

          {/* ── 每日趋势 ── */}
          <Card
            title={<Space><BarChartOutlined /> 每日趋势</Space>}
            style={{ borderRadius: RADIUS.lg, marginBottom: 16 }}
            styles={{ body: { minHeight: 300 } }}
          >
            {trend.length > 0 ? (
              <Line
                data={trend.flatMap((d) => [
                  { date: d.date, value: d.conversations, type: '会话数' },
                  { date: d.date, value: d.toolCalls, type: '工具调用数' },
                ])}
                xField="date"
                yField="value"
                colorField="type"
                style={{ lineWidth: 2 }}
                axis={{
                  x: { title: '日期' },
                  y: { title: '数量' },
                }}
                legend={{ color: { position: 'top' } }}
                height={280}
              />
            ) : (
              <Empty description="无趋势数据" style={{ padding: '40px 0' }} />
            )}
          </Card>

          {/* ── 工具性能明细表 ── */}
          <Card
            title={<Space><ToolOutlined /> 工具性能明细</Space>}
            style={{ borderRadius: RADIUS.lg }}
          >
            <Table
              dataSource={toolFreq}
              columns={toolColumns}
              rowKey="toolName"
              pagination={false}
              size="middle"
              scroll={{ x: 600 }}
            />
          </Card>
        </>
      )}
    </PageContainer>
  )
}
