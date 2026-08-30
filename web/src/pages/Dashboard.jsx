import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Col, Empty, Progress, Row, Space, Statistic, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { taskApi } from '../api/client'
import PageContainer from '../components/PageContainer'
import { BRAND, CLAY, CLAY_SHADOW } from '../theme'

const { Text } = Typography

// 四色染色统计卡：紫/珊瑚/黄/绿，各配 emoji 图标盒
const statCards = [
  { key: 'total', emoji: '📋', tint: CLAY.purpleTint, color: BRAND.primary },
  { key: 'completed', emoji: '✅', tint: CLAY.mintTint, color: CLAY.mint },
  { key: 'pending', emoji: '⏳', tint: CLAY.mustardTint, color: '#D4A017' },
  { key: 'percent', emoji: '📈', tint: CLAY.coralTint, color: CLAY.coral },
]

export default function Dashboard() {
  const [tasks, setTasks] = useState([])
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const loadTasks = useCallback(() => {
    setLoading(true)
    taskApi
      .list()
      .then(setTasks)
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadTasks()
  }, [loadTasks])

  const total = tasks.length
  const completed = tasks.filter((t) => t.completed).length
  const pending = total - completed
  const percent = total ? Math.round((completed / total) * 100) : 0

  const values = { total, completed, pending, percent }

  const recentTasks = [...tasks].reverse().slice(0, 5)

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '标题', dataIndex: 'title' },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (v) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'completed',
      width: 100,
      render: (v) =>
        v ? (
          <Tag style={{ background: CLAY.mintTint, color: CLAY.mint }}>已完成</Tag>
        ) : (
          <Tag style={{ background: CLAY.mustardTint, color: '#D4A017' }}>进行中</Tag>
        ),
    },
    {
      title: '更新时间',
      dataIndex: 'id',
      width: 140,
      render: () => dayjs().format('YYYY-MM-DD HH:mm'),
    },
  ]

  return (
    <PageContainer
      title="仪表盘"
      emoji="🏠"
      breadcrumb={[{ title: '首页' }, { title: '仪表盘' }]}
      subTitle="任务概览与快捷操作"
      extra={
        <Button icon={<ReloadOutlined />} onClick={loadTasks} loading={loading}>
          刷新
        </Button>
      }
    >
      <Row gutter={[20, 20]}>
        {statCards.map(({ key, emoji, tint, color }) => (
          <Col xs={24} sm={12} xl={6} key={key}>
            <Card className="clay-card-hover" style={{ background: tint }}>
              <Space size={16} align="center">
                <span className="clay-icon-box" style={{ background: '#fff', boxShadow: CLAY_SHADOW.small }}>
                  {emoji}
                </span>
                {key === 'percent' ? (
                  <Statistic
                    title="任务完成率"
                    value={values[key]}
                    suffix="%"
                    valueStyle={{ color, fontWeight: 800 }}
                    loading={loading}
                  />
                ) : (
                  <Statistic
                    title={{ total: '任务总数', completed: '已完成', pending: '进行中' }[key]}
                    value={values[key]}
                    valueStyle={{ color, fontWeight: 800 }}
                    loading={loading}
                  />
                )}
              </Space>
              {key === 'percent' && (
                <Progress
                  percent={percent}
                  showInfo={false}
                  strokeColor={{ '0%': BRAND.primary, '100%': CLAY.coral }}
                  size="small"
                  style={{ marginTop: 12 }}
                />
              )}
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[20, 20]} style={{ marginTop: 20 }}>
        <Col xs={24} xl={14}>
          <Card
            title="📌 最近任务"
            extra={
              <Button type="link" onClick={() => navigate('/tasks')}>
                查看全部
              </Button>
            }
          >
            <Table
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={recentTasks}
              loading={loading}
              pagination={false}
              locale={{ emptyText: <Empty description="暂无任务" /> }}
            />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card title="🚀 快捷入口">
            <Space direction="vertical" style={{ width: '100%' }} size={14}>
              <Button
                type="primary"
                block
                size="large"
                style={{ height: 52, fontSize: 15, fontWeight: 800 }}
                onClick={() => navigate('/chat')}
              >
                💬 开始 AI 对话
              </Button>
              <Button block size="large" style={{ height: 52, fontSize: 15, fontWeight: 700 }} onClick={() => navigate('/tasks')}>
                📝 管理任务
              </Button>
              <Card
                size="small"
                type="inner"
                title="系统状态"
                style={{ background: CLAY.base, boxShadow: 'none', borderRadius: 20 }}
              >
                <Space direction="vertical" size={4}>
                  <Text type="secondary">
                    <span style={{ marginRight: 6 }}>🤖</span>
                    JARVIS Agent 运行正常，支持工具调用
                  </Text>
                  <Text type="secondary">
                    <span style={{ marginRight: 6 }}>⚡</span>
                    当前对话接口：/api/agent/chat/stream（SSE 流式）
                  </Text>
                </Space>
              </Card>
            </Space>
          </Card>
        </Col>
      </Row>
    </PageContainer>
  )
}
