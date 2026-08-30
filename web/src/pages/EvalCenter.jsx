import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import { Line } from '@ant-design/charts'
import { ReloadOutlined } from '@ant-design/icons'
import PageContainer from '../components/PageContainer'
import { evalApi } from '../api/client'
import { CLAY, CLAY_SHADOW, RADIUS } from '../theme'

const { Text } = Typography

// ---------- 指标达标语义（与 EvalReportRenderer 基线阈值一致） ----------
const METRIC_RULES = {
  recallAt4: { label: 'Recall@4', min: 0.8, fmt: (v) => v.toFixed(3), upGood: true },
  mrr: { label: 'MRR', min: 0.6, fmt: (v) => v.toFixed(3), upGood: true },
  separation: { label: '区分度', min: 0.1, fmt: (v) => v.toFixed(3), upGood: true },
  wrongInjectRate: { label: '误注入率', max: 0.25, fmt: (v) => v.toFixed(2), upGood: false },
  injectRecall: { label: '注入召回', min: 0.8, fmt: (v) => v.toFixed(3), upGood: true },
}

/** 指标是否达标 */
function isPass(key, value) {
  const rule = METRIC_RULES[key]
  if (!rule) return true
  if (rule.min !== undefined) return value >= rule.min
  return value <= rule.max
}

const VERDICT_TAG = {
  flat: { color: 'default', text: '持平' },
  'up-good': { color: 'green', text: '↑ 变好' },
  'down-good': { color: 'green', text: '↓ 变好' },
  'up-bad': { color: 'red', text: '↑ 变坏' },
  'down-bad': { color: 'red', text: '↓ 变坏' },
}

/** 指标卡：最新值 + 达标 Tag + 与上次的变化 */
function MetricCard({ metricKey, value, delta }) {
  const rule = METRIC_RULES[metricKey]
  const pass = isPass(metricKey, value)
  const diff = delta?.verdict && delta.verdict !== 'flat' ? delta : null
  return (
    <Card
      style={{ borderRadius: RADIUS.card, boxShadow: CLAY_SHADOW.raised, background: '#fff' }}
      styles={{ body: { padding: '18px 22px' } }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontWeight: 700 }}>
          {rule.label}
        </Text>
        <Tag
          color={pass ? 'success' : 'error'}
          style={{ border: 'none', fontWeight: 700, boxShadow: CLAY_SHADOW.small, borderRadius: RADIUS.pill }}
        >
          {pass ? '达标' : '未达标'}
        </Tag>
      </div>
      <div style={{ fontSize: 30, fontWeight: 800, color: CLAY.ink, margin: '6px 0 2px' }}>
        {rule.fmt(value)}
      </div>
      <Space size={6}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          基线 {rule.min !== undefined ? `≥ ${rule.min}` : `≤ ${rule.max}`}
        </Text>
        {diff && (
          <Text
            style={{
              fontSize: 12,
              fontWeight: 700,
              color: VERDICT_TAG[diff.verdict].color === 'green' ? CLAY.mint : CLAY.coral,
            }}
          >
            {diff.delta >= 0 ? '+' : ''}
            {diff.delta.toFixed(3)}
          </Text>
        )}
      </Space>
    </Card>
  )
}

/** 趋势图：Recall@4 / MRR 随运行次数的时间轴 */
function TrendChart({ runs }) {
  const data = useMemo(
    () =>
      runs.flatMap((r, i) => [
        { run: `#${i + 1}`, value: r.metrics.recallAt4, metric: 'Recall@4' },
        { run: `#${i + 1}`, value: r.metrics.mrr, metric: 'MRR' },
      ]),
    [runs]
  )
  if (data.length < 2) {
    return (
      <Empty
        description="趋势图需要至少两次评测归档（跑 ./run-eval.sh 多次）"
        style={{ padding: '40px 0' }}
      />
    )
  }
  return (
    <Line
      data={data}
      xField="run"
      yField="value"
      colorField="metric"
      height={260}
      style={{ lineWidth: 3 }}
      scale={{ y: { domainMin: 0, domainMax: 1 } }}
      axis={{
        x: { title: '评测运行（时间序）' },
        y: { title: null },
      }}
      legend={{ color: { position: 'top' } }}
    />
  )
}

// ---------- 用例明细 ----------
const caseColumns = [
  { title: 'ID', dataIndex: 'id', width: 130 },
  { title: '类型', dataIndex: 'type', width: 100 },
  { title: '问题', dataIndex: 'question', ellipsis: true },
  {
    title: '期望文档',
    dataIndex: 'expectDoc',
    width: 150,
    ellipsis: true,
    render: (v) => v || '—（无关题）',
  },
  {
    title: 'Rank',
    dataIndex: 'relevantRank',
    width: 70,
    render: (v, row) => (row.irrelevant ? '—' : v > 0 ? `#${v}` : '❌ 未命中'),
  },
  { title: 'Top1', dataIndex: 'top1Score', width: 80, render: (v) => v?.toFixed(3) },
  {
    title: '注入',
    dataIndex: 'injected',
    width: 100,
    render: (v, row) =>
      row.irrelevant ? (
        v ? <Tag color="error">⚠️ 误注入</Tag> : <Tag color="success">正确拒绝</Tag>
      ) : v ? (
        <Tag color="success">是</Tag>
      ) : (
        <Tag>模糊带</Tag>
      ),
  },
  { title: '耗时', dataIndex: 'latencyMs', width: 80, render: (v) => `${v}ms` },
]

/** 用例明细表：类型 / 结果筛选，失败用例展开查看 Top-K 命中详情 */
function CaseDetailTable({ cases }) {
  const [type, setType] = useState(null)
  const [result, setResult] = useState(null)

  const filtered = useMemo(
    () =>
      (cases || []).filter((c) => {
        if (type && c.type !== type) return false
        if (result === 'miss' && (c.irrelevant || c.relevantRank > 0)) return false
        if (result === 'hit' && (c.irrelevant || c.relevantRank === 0)) return false
        if (result === 'wrongInject' && !(c.irrelevant && c.injected)) return false
        return true
      }),
    [cases, type, result]
  )

  const types = useMemo(() => [...new Set((cases || []).map((c) => c.type))], [cases])

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="全部类型"
          style={{ width: 140 }}
          value={type}
          onChange={setType}
          options={types.map((t) => ({ value: t, label: t }))}
        />
        <Select
          allowClear
          placeholder="全部结果"
          style={{ width: 140 }}
          value={result}
          onChange={setResult}
          options={[
            { value: 'hit', label: '命中' },
            { value: 'miss', label: '未命中' },
            { value: 'wrongInject', label: '误注入' },
          ]}
        />
        <Text type="secondary">{filtered.length} 条</Text>
      </Space>
      <Table
        rowKey="id"
        size="small"
        columns={caseColumns}
        dataSource={filtered}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        expandable={{
          rowExpandable: (row) => !row.irrelevant,
          expandedRowRender: (row) =>
            row.relevantRank === 0 ? (
              <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>
                {row.missDetail || '（无详情）'}
              </pre>
            ) : (
              <Text type="secondary">
                命中于第 {row.relevantRank} 位（Top-K={4}），Top1={row.top1Score?.toFixed(3)}
                ，{row.injected ? '已自动注入对话' : 'Top1 未达 inject-score，落在模糊带（agent 工具路径兜底）'}
              </Text>
            ),
        }}
      />
    </div>
  )
}

/** 历史运行 Drawer：完整 summary + 与前一次 diff + 用例明细 */
function RunDetailDrawer({ runId, open, onClose }) {
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open || !runId) return
    setLoading(true)
    evalApi
      .historyDetail(runId)
      .then(setDetail)
      .catch(() => onClose())
      .finally(() => setLoading(false))
  }, [runId, open, onClose])

  const summary = detail?.summary
  const diff = detail?.diff

  return (
    <Drawer
      title={`评测详情 · ${runId}`}
      open={open}
      onClose={onClose}
      width={880}
      destroyOnHidden
    >
      {loading || !summary ? (
        <Empty description={loading ? '加载中…' : '无数据'} />
      ) : (
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <Card size="small" style={{ borderRadius: RADIUS.iconBox }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="Suite">{summary.suite}</Descriptions.Item>
              <Descriptions.Item label="时间">{summary.timestamp?.slice(0, 19)}</Descriptions.Item>
              <Descriptions.Item label="Git">{summary.gitCommit?.slice(0, 10)}</Descriptions.Item>
              <Descriptions.Item label="用例数">
                {Object.entries(summary.caseCounts || {})
                  .map(([k, v]) => `${k}=${v}`)
                  .join('  ')}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card
            size="small"
            title="与上次对比"
            style={{ borderRadius: RADIUS.iconBox }}
          >
            {!diff ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="基线首次建立（无历史归档可对比）" />
            ) : (
              <Table
                rowKey={(r) => r.metric}
                size="small"
                pagination={false}
                dataSource={Object.entries(diff.metrics).map(([metric, d]) => ({
                  metric,
                  ...d,
                }))}
                columns={[
                  { title: '指标', dataIndex: 'metric' },
                  { title: '上次', render: (_, r) => r.prev.toFixed(3) },
                  { title: '本次', render: (_, r) => r.current.toFixed(3) },
                  {
                    title: '变化',
                    render: (_, r) => (
                      <span
                        style={{
                          fontWeight: 700,
                          color:
                            r.verdict === 'flat'
                              ? CLAY.inkSoft
                              : VERDICT_TAG[r.verdict].color === 'green'
                                ? CLAY.mint
                                : CLAY.coral,
                        }}
                      >
                        {r.delta >= 0 ? '+' : ''}
                        {r.delta.toFixed(3)}
                      </span>
                    ),
                  },
                  {
                    title: '评价',
                    render: (_, r) => (
                      <Tag color={VERDICT_TAG[r.verdict].color}>
                        {VERDICT_TAG[r.verdict].text}
                        {r.significant ? ' ⚠️' : ''}
                      </Tag>
                    ),
                  },
                ]}
              />
            )}
          </Card>

          <Card
            size="small"
            title="用例明细"
            style={{ borderRadius: RADIUS.iconBox }}
          >
            <CaseDetailTable cases={summary.cases} />
          </Card>
        </Space>
      )}
    </Drawer>
  )
}

// ---------- 候选池 ----------
const TYPE_OPTIONS = ['精确词', '同义改写', '表格数字', '组合'].map((t) => ({ value: t, label: t }))

function PromoteModal({ candidate, open, onCancel, onOk }) {
  const [form] = Form.useForm()
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        type: null,
        expectDoc: candidate?.expectDoc || null,
        expectChunkKeywords: [],
        expectAnswerKeywords: [],
      })
    }
  }, [open, candidate, form])

  const submit = async () => {
    const values = await form.validateFields()
    setSubmitting(true)
    try {
      await onOk(values)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="转正为标注用例"
      open={open}
      onCancel={onCancel}
      onOk={submit}
      confirmLoading={submitting}
      okText="写入标注集"
    >
      <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
        <Descriptions.Item label="问题">{candidate?.question}</Descriptions.Item>
        {candidate?.note && (
          <Descriptions.Item label="备注">{candidate.note}</Descriptions.Item>
        )}
      </Descriptions>
      <Form form={form} layout="vertical">
        <Form.Item name="type" label="查询类型" rules={[{ required: true, message: '选择查询类型' }]}>
          <Select options={TYPE_OPTIONS} placeholder="精确词 / 同义改写 / 表格数字 / 组合" />
        </Form.Item>
        <Form.Item
          name="expectDoc"
          label="期望命中文档"
          rules={[{ required: true, message: '填写期望文档标题（与知识库文档名一致）' }]}
        >
          <Input placeholder="如：JARVIS team handbook" />
        </Form.Item>
        <Form.Item
          name="expectChunkKeywords"
          label="块级关键词（任一命中即算命中块）"
          tooltip="用于命中判定：文档匹配且块内容含任一关键词"
        >
          <Select mode="tags" placeholder="回车添加多个关键词" />
        </Form.Item>
        <Form.Item
          name="expectAnswerKeywords"
          label="答案要素关键词（生成层校验）"
          tooltip="全部出现在回答里才算要素命中"
        >
          <Select mode="tags" placeholder="回车添加多个关键词" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function CandidatesPanel() {
  const [status, setStatus] = useState('pending')
  const [data, setData] = useState({ items: [], total: 0 })
  const [loading, setLoading] = useState(false)
  const [promoting, setPromoting] = useState(null)
  const [page, setPage] = useState(1)

  const load = useCallback(() => {
    setLoading(true)
    evalApi
      .candidates({ status, page, size: 20 })
      .then(setData)
      .finally(() => setLoading(false))
  }, [status, page])

  useEffect(() => {
    load()
  }, [load])

  const handlePromote = async (values) => {
    await evalApi.promoteCandidate(promoting.id, {
      type: values.type,
      expectDoc: values.expectDoc,
      expectChunkKeywords: values.expectChunkKeywords || [],
      expectAnswerKeywords: values.expectAnswerKeywords || [],
    })
    message.success('已转正并写入标注集（重跑评测后生效）')
    setPromoting(null)
    load()
  }

  const handleDiscard = async (id) => {
    await evalApi.discardCandidate(id)
    message.success('已丢弃')
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '问题', dataIndex: 'question', ellipsis: true },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: (v) => v || '—' },
    { title: '来源', dataIndex: 'source', width: 110, render: (v) => v || '—' },
    { title: '提交时间', dataIndex: 'createdAt', width: 170, render: (v) => v || '—' },
    {
      title: '操作',
      width: 150,
      render: (_, row) =>
        status === 'pending' ? (
          <Space>
            <Button type="primary" size="small" onClick={() => setPromoting(row)}>
              转正
            </Button>
            <Button size="small" danger onClick={() => handleDiscard(row.id)}>
              丢弃
            </Button>
          </Space>
        ) : (
          <Text type="secondary">{status === 'promoted' ? '已转正' : '已丢弃'}</Text>
        ),
    },
  ]

  return (
    <Card
      title="候选池 · 坏 case 流水线"
      extra={
        <Text type="secondary" style={{ fontSize: 12 }}>
          Chat 中点 👎 提交；转正后追加写入标注集（eval_cases.json）
        </Text>
      }
      style={{ borderRadius: RADIUS.card, boxShadow: CLAY_SHADOW.raised, background: '#fff' }}
    >
      <Tabs
        activeKey={status}
        onChange={(k) => {
          setStatus(k)
          setPage(1)
        }}
        items={[
          { key: 'pending', label: '待处理' },
          { key: 'promoted', label: '已转正' },
          { key: 'discarded', label: '已丢弃' },
        ]}
      />
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={data.items}
        pagination={{
          total: data.total,
          current: page,
          pageSize: 20,
          showSizeChanger: false,
          onChange: setPage,
        }}
      />
      <PromoteModal
        candidate={promoting}
        open={!!promoting}
        onCancel={() => setPromoting(null)}
        onOk={handlePromote}
      />
    </Card>
  )
}

// ---------- 主页面 ----------
export default function EvalCenter() {
  const [items, setItems] = useState(null)
  const [detailRunId, setDetailRunId] = useState(null)

  const load = useCallback(() => {
    evalApi.history().then((d) => setItems(d.items || []))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const retrievalRuns = useMemo(
    () => (items || []).filter((r) => r.suite === 'retrieval'),
    [items]
  )
  const latest = retrievalRuns[retrievalRuns.length - 1]
  const latestDetail = null // diff 信息在 Drawer 中查看，指标卡增量单独取

  // 与前一次运行的 delta（指标卡角标）；懒加载最新一次的 diff
  const [latestDiff, setLatestDiff] = useState(null)
  useEffect(() => {
    if (!latest) return
    evalApi
      .historyDetail(latest.runId)
      .then((d) => setLatestDiff(d.diff))
      .catch(() => {})
  }, [latest])

  const historyColumns = [
    { title: 'RunId', dataIndex: 'runId' },
    { title: 'Suite', dataIndex: 'suite', width: 110 },
    {
      title: '时间',
      dataIndex: 'timestamp',
      width: 180,
      render: (v) => v?.slice(0, 19).replace('T', ' '),
    },
    {
      title: 'Git',
      dataIndex: 'gitCommit',
      width: 110,
      render: (v) => <Text code>{v?.slice(0, 7)}</Text>,
    },
    {
      title: 'Recall@4',
      width: 100,
      render: (_, r) => r.metrics?.recallAt4?.toFixed(3),
    },
    { title: 'MRR', width: 90, render: (_, r) => r.metrics?.mrr?.toFixed(3) },
    {
      title: '',
      width: 80,
      render: (_, r) => (
        <Button size="small" onClick={() => setDetailRunId(r.runId)}>
          详情
        </Button>
      ),
    },
  ]

  return (
    <PageContainer
      title="评测中心"
      emoji="📊"
      emojiBg={CLAY.mustardTint}
      subTitle="RAG 检索/生成质量 · 历史趋势 · 候选池"
      extra={
        <Button icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      }
    >
      {items === null ? (
        <Card loading style={{ borderRadius: RADIUS.card }} />
      ) : items.length === 0 ? (
        <Card style={{ borderRadius: RADIUS.card, boxShadow: CLAY_SHADOW.raised }}>
          <Empty description="还没有评测归档">
            <Text type="secondary">
              在项目根目录执行 <Text code>./run-eval.sh</Text> 生成首次基线
              （需本地 Ollama 与后端运行中）
            </Text>
          </Empty>
        </Card>
      ) : (
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          {/* 指标卡：最新一次检索层运行 */}
          {latest && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
              {['recallAt4', 'mrr', 'wrongInjectRate', 'injectRecall'].map((key) => (
                <MetricCard
                  key={key}
                  metricKey={key}
                  value={latest.metrics[key]}
                  delta={latestDiff?.metrics?.[key]}
                />
              ))}
            </div>
          )}

          {/* 趋势图 */}
          <Card
            title="指标趋势（检索层）"
            style={{ borderRadius: RADIUS.card, boxShadow: CLAY_SHADOW.raised, background: '#fff' }}
          >
            <TrendChart runs={retrievalRuns} />
          </Card>

          {/* 历史运行列表 */}
          <Card
            title="评测历史"
            style={{ borderRadius: RADIUS.card, boxShadow: CLAY_SHADOW.raised, background: '#fff' }}
          >
            <Table
              rowKey="runId"
              size="small"
              dataSource={[...items].reverse()}
              columns={historyColumns}
              pagination={false}
              onRow={(r) => ({
                onClick: () => setDetailRunId(r.runId),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>

          {/* 候选池 triage */}
          <CandidatesPanel />
        </Space>
      )}

      <RunDetailDrawer
        runId={detailRunId}
        open={!!detailRunId}
        onClose={() => setDetailRunId(null)}
      />
    </PageContainer>
  )
}
