import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Empty,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  MessageOutlined,
  RobotOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { taskApi } from '../api/client'

const { Title, Text } = Typography

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
        v ? <Tag color="success">已完成</Tag> : <Tag color="warning">进行中</Tag>,
    },
    {
      title: '更新时间',
      dataIndex: 'id',
      width: 140,
      render: () => dayjs().format('YYYY-MM-DD HH:mm'),
    },
  ]

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>
        仪表盘
      </Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <Card>
            <Statistic
              title="任务总数"
              value={total}
              prefix={<UnorderedListOutlined />}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card>
            <Statistic
              title="已完成"
              value={completed}
              valueStyle={{ color: '#52c41a' }}
              prefix={<CheckCircleOutlined />}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card>
            <Statistic
              title="进行中"
              value={pending}
              valueStyle={{ color: '#faad14' }}
              prefix={<ClockCircleOutlined />}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card>
            <Statistic
              title="任务完成率"
              value={percent}
              suffix="%"
              prefix={<RobotOutlined />}
              loading={loading}
            />
            <Progress percent={percent} size="small" style={{ marginTop: 8 }} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={14}>
          <Card
            title="最近任务"
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
          <Card title="快捷入口">
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <Button
                type="primary"
                icon={<MessageOutlined />}
                block
                size="large"
                onClick={() => navigate('/chat')}
              >
                开始 AI 对话
              </Button>
              <Button
                icon={<UnorderedListOutlined />}
                block
                size="large"
                onClick={() => navigate('/tasks')}
              >
                管理任务
              </Button>
              <Card size="small" type="inner" title="系统状态">
                <Space direction="vertical" size={4}>
                  <Text type="secondary">
                    <RobotOutlined /> JARVIS Agent 运行正常，支持工具调用
                  </Text>
                  <Text type="secondary">
                    当前对话接口：/api/agent/chat/stream（SSE 流式）
                  </Text>
                </Space>
              </Card>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
