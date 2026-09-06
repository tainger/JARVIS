import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Empty,
  Popconfirm,
  Row,
  Skeleton,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  HeartOutlined,
  ReloadOutlined,
  FileTextOutlined,
  SearchOutlined,
  DeleteOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { Bar } from '@ant-design/charts'
import { useNavigate } from 'react-router-dom'
import PageContainer from '../components/PageContainer'
import { knowledgeHealthApi, knowledgeApi } from '../api/client'
import { BRAND, CLAY, CLAY_SHADOW, RADIUS } from '../theme'

const { Text } = Typography

export default function KnowledgeHealth() {
  const navigate = useNavigate()
  const [zombieData, setZombieData] = useState(null)
  const [blindSpots, setBlindSpots] = useState([])
  const [docHeat, setDocHeat] = useState([])
  const [loading, setLoading] = useState(true)
  const [selectedZombieIds, setSelectedZombieIds] = useState([])

  const load = useCallback(() => {
    setLoading(true)
    Promise.all([
      knowledgeHealthApi.zombieDocs(),
      knowledgeHealthApi.blindSpots(),
      knowledgeHealthApi.docHeat(),
    ])
      .then(([z, bs, dh]) => {
        setZombieData(z)
        setBlindSpots(bs || [])
        setDocHeat(dh || [])
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const handleBatchDelete = async () => {
    if (selectedZombieIds.length === 0) return
    setLoading(true)
    let ok = 0
    for (const id of selectedZombieIds) {
      try { await knowledgeApi.remove(id); ok++ }
      catch (e) { /* continue */ }
    }
    message.success(`已删除 ${ok} 个僵尸文档`)
    setSelectedZombieIds([])
    load()
  }

  const handleAddDocFromBlindSpot = (query) => {
    navigate('/knowledge', { state: { importTitle: query } })
  }

  const zombieColumns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    { title: '片段数', dataIndex: 'chunk_count', key: 'chunk_count', width: 80, align: 'center' },
    {
      title: '导入时间', dataIndex: 'created_at', key: 'created_at', width: 180,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
  ]

  const blindSpotColumns = [
    { title: '查询词', dataIndex: 'query', key: 'query', ellipsis: true },
    { title: '出现次数', dataIndex: 'traceCount', key: 'traceCount', width: 90, align: 'center' },
    {
      title: '最近出现', dataIndex: 'lastSeen', key: 'lastSeen', width: 180,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action', width: 100, align: 'center',
      render: (_, record) => (
        <Button
          type="link"
          size="small"
          icon={<PlusOutlined />}
          onClick={() => handleAddDocFromBlindSpot(record.query)}
        >
          添加文档
        </Button>
      ),
    },
  ]

  const heatChartData = docHeat
    .filter((d) => d.referenceCount > 0)
    .map((d) => ({
      title: d.title.length > 15 ? d.title.substring(0, 15) + '…' : d.title,
      count: d.referenceCount,
      hasDislike: d.hasDislike,
    }))

  return (
    <PageContainer
      title="知识库健康度"
      emoji="🏥"
      breadcrumb={[{ title: '首页', to: '/' }, { title: '知识健康' }]}
      description="监控知识库文档的引用热度、僵尸文档和搜索盲区，数据驱动优化 RAG 质量。"
      extra={
        <ReloadOutlined
          onClick={load}
          style={{ fontSize: 18, cursor: 'pointer', color: CLAY.inkSoft }}
        />
      }
    >
      {loading ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : (
        <>
          {/* ── 统计卡片 ── */}
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={12} sm={8}>
              <Card className="clay-card-hover" style={{ background: CLAY.purpleTint, borderRadius: RADIUS.lg }}>
                <Space size={12} align="center">
                  <span className="clay-icon-box" style={{ background: '#fff', boxShadow: CLAY_SHADOW.small }}>
                    <FileTextOutlined style={{ color: BRAND.primary }} />
                  </span>
                  <Statistic title="总文档数" value={zombieData?.totalDocuments ?? 0} valueStyle={{ color: BRAND.primary, fontWeight: 800 }} />
                </Space>
              </Card>
            </Col>
            <Col xs={12} sm={8}>
              <Card className="clay-card-hover" style={{ background: CLAY.coralTint, borderRadius: RADIUS.lg }}>
                <Space size={12} align="center">
                  <span className="clay-icon-box" style={{ background: '#fff', boxShadow: CLAY_SHADOW.small }}>
                    🧟
                  </span>
                  <Statistic title="僵尸文档" value={zombieData?.zombieCount ?? 0} valueStyle={{ color: CLAY.coral, fontWeight: 800 }} />
                </Space>
              </Card>
            </Col>
            <Col xs={24} sm={8}>
              <Card className="clay-card-hover" style={{ background: CLAY.mustardTint, borderRadius: RADIUS.lg }}>
                <Space size={12} align="center">
                  <span className="clay-icon-box" style={{ background: '#fff', boxShadow: CLAY_SHADOW.small }}>
                    🔍
                  </span>
                  <Statistic title="盲区关键词" value={blindSpots.length} valueStyle={{ color: '#D4A017', fontWeight: 800 }} />
                </Space>
              </Card>
            </Col>
          </Row>

          {/* ── 文档引用热度 ── */}
          <Card
            title={<Space><HeartOutlined /> 文档引用热度排行</Space>}
            style={{ borderRadius: RADIUS.lg, marginBottom: 16 }}
            styles={{ body: { minHeight: 280 } }}
          >
            {heatChartData.length > 0 ? (
              <Bar
                data={heatChartData}
                xField="count"
                yField="title"
                colorField={(d) => d.hasDislike ? CLAY.coral : BRAND.primary}
                style={{ maxWidth: 40 }}
                axis={{
                  x: { title: '引用次数' },
                  y: { title: null },
                }}
                label={{ text: 'count', position: 'right' }}
                height={280}
              />
            ) : (
              <Empty description="暂无引用记录，开始对话后 Agent 的知识检索会生成引用数据" style={{ padding: '40px 0' }} />
            )}
          </Card>

          {/* ── 僵尸文档 + 盲区关键词 ── */}
          <Row gutter={[16, 16]}>
            <Col xs={24} xl={12}>
              <Card
                title={<Space><DeleteOutlined /> 僵尸文档</Space>}
                style={{ borderRadius: RADIUS.lg }}
                extra={
                  selectedZombieIds.length > 0 && (
                    <Popconfirm
                      title={`确认删除 ${selectedZombieIds.length} 个僵尸文档？`}
                      onConfirm={handleBatchDelete}
                      okText="删除"
                      cancelText="取消"
                    >
                      <Button danger size="small" icon={<DeleteOutlined />}>
                        批量删除
                      </Button>
                    </Popconfirm>
                  )
                }
              >
                <Table
                  dataSource={zombieData?.items || []}
                  columns={zombieColumns}
                  rowKey="id"
                  size="small"
                  pagination={{ pageSize: 5, showSizeChanger: false }}
                  rowSelection={{
                    selectedRowKeys: selectedZombieIds,
                    onChange: (keys) => setSelectedZombieIds(keys),
                  }}
                />
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card
                title={<Space><SearchOutlined /> 知识盲区关键词</Space>}
                style={{ borderRadius: RADIUS.lg }}
              >
                {blindSpots.length > 0 ? (
                  <Table
                    dataSource={blindSpots}
                    columns={blindSpotColumns}
                    rowKey={(r) => r.query}
                    size="small"
                    pagination={{ pageSize: 5, showSizeChanger: false }}
                  />
                ) : (
                  <Empty description="暂无盲区记录" style={{ padding: '40px 0' }} />
                )}
              </Card>
            </Col>
          </Row>
        </>
      )}
    </PageContainer>
  )
}
