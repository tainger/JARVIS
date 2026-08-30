import { useCallback, useEffect, useState } from 'react'
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import { knowledgeApi } from '../api/client'
import PageContainer from '../components/PageContainer'
import { CLAY, CLAY_SHADOW } from '../theme'

const { Paragraph } = Typography

export default function Knowledge() {
  const { message } = App.useApp()
  const [docs, setDocs] = useState([])
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  // 检索测试
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState([])

  // 查看文档
  const [viewing, setViewing] = useState(null)

  // 批量导入
  const [batchFiles, setBatchFiles] = useState([]) // [{file, title, status, msg}]
  const [batchRunning, setBatchRunning] = useState(false)
  const [importTab, setImportTab] = useState('single')

  const loadData = useCallback(() => {
    setLoading(true)
    Promise.all([knowledgeApi.list(), knowledgeApi.stats()])
      .then(([list, s]) => {
        setDocs(list)
        setStats(s)
      })
      .catch(() => message.error('加载知识库失败，请检查后端服务'))
      .finally(() => setLoading(false))
  }, [message])

  useEffect(() => {
    loadData()
  }, [loadData])

  const readFile = async (file) => {
    const text = await file.text()
    form.setFieldsValue({
      content: text,
      title: form.getFieldValue('title') || file.name.replace(/\.[^.]+$/, ''),
      fileName: file.name,
    })
    message.success(`已读取 ${file.name}（${text.length} 字符）`)
    return false // 阻止自动上传，由提交时统一走 JSON 接口
  }

  const handleImport = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const doc = await knowledgeApi.create(values)
      message.success(`导入成功：${doc.title}（${doc.chunkCount} 个片段）`)
      setModalOpen(false)
      form.resetFields()
      loadData()
    } catch (e) {
      message.error('导入失败：' + (e?.response?.data?.detail || e.message))
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id) => {
    try {
      await knowledgeApi.remove(id)
      message.success('文档已删除')
      loadData()
    } catch (e) {
      message.error('删除失败：' + (e?.response?.data?.detail || e.message))
    }
  }

  const handleSearch = async () => {
    if (!query.trim()) return
    setSearching(true)
    try {
      const res = await knowledgeApi.search(query.trim())
      setHits(res.hits)
      if (!res.hits.length) message.info('没有命中相关知识片段')
    } catch (e) {
      message.error('检索失败：' + (e?.response?.data?.detail || e.message))
    } finally {
      setSearching(false)
    }
  }

  const handleView = async (id) => {
    try {
      setViewing(await knowledgeApi.get(id))
    } catch (e) {
      message.error('加载文档失败')
    }
  }

  // ===== 批量导入 =====

  /** 选择多文件后收集到待导入列表（阻止自动上传） */
  const collectBatchFiles = (file) => {
    const title = file.name.replace(/\.[^.]+$/, '')
    setBatchFiles((prev) => [
      ...prev,
      { file, title, status: 'pending', msg: '', chunks: 0 },
    ])
    return false // 阻止 Upload 自动发请求
  }

  /** 批量导入执行：逐个读文本→调 API→更新状态（串行，避免 Ollama embedding 超时） */
  const handleBatchImport = async () => {
    if (!batchFiles.length) return
    setBatchRunning(true)
    let ok = 0
    let fail = 0
    for (let i = 0; i < batchFiles.length; i++) {
      const item = batchFiles[i]
      if (item.status === 'ok') continue
      // 标记进行中
      setBatchFiles((prev) =>
        prev.map((p, idx) => (idx === i ? { ...p, status: 'running' } : p))
      )
      try {
        const content = await item.file.text()
        if (!content || !content.trim()) {
          throw new Error('文件内容为空')
        }
        const doc = await knowledgeApi.create({
          title: item.title || item.file.name,
          fileName: item.file.name,
          content,
        })
        ok++
        setBatchFiles((prev) =>
          prev.map((p, idx) =>
            idx === i ? { ...p, status: 'ok', msg: `${doc.chunkCount} 片段`, chunks: doc.chunkCount } : p
          )
        )
      } catch (e) {
        fail++
        const errMsg = e?.response?.data?.detail || e.message
        setBatchFiles((prev) =>
          prev.map((p, idx) => (idx === i ? { ...p, status: 'fail', msg: errMsg } : p))
        )
      }
    }
    setBatchRunning(false)
    if (fail === 0) {
      message.success(`批量导入完成：${ok} 篇文档全部成功`)
      setModalOpen(false)
      setBatchFiles([])
      loadData()
    } else {
      message.warning(`批量导入完成：成功 ${ok} 篇，失败 ${fail} 篇`)
      loadData() // 刷新已成功的部分
    }
  }

  /** 移除待导入列表中的文件 */
  const removeBatchFile = (idx) => {
    setBatchFiles((prev) => prev.filter((_, i) => i !== idx))
  }

  /** 清空待导入列表 */
  const clearBatchFiles = () => setBatchFiles([])

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '标题', dataIndex: 'title' },
    {
      title: '来源文件',
      dataIndex: 'fileName',
      width: 180,
      render: (v) => (v ? <Tag icon={<FileTextOutlined />}>{v}</Tag> : '-'),
    },
    { title: '片段数', dataIndex: 'chunkCount', width: 90 },
    { title: '字符数', dataIndex: 'contentLength', width: 100 },
    { title: '导入时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作',
      width: 150,
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => handleView(record.id)}
          >
            查看
          </Button>
          <Popconfirm
            title="删除后向量索引同步失效，确定删除？"
            okText="删除"
            cancelText="取消"
            onConfirm={() => handleDelete(record.id)}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <PageContainer
      title="知识库"
      emoji="📚"
      emojiBg={CLAY.mintTint}
      breadcrumb={[{ title: '首页', to: '/dashboard' }, { title: '知识库' }]}
      subTitle="RAG 知识库：导入文档，自动分块并向量化，对话时自动检索注入"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          导入文档
        </Button>
      }
    >
      <Card style={{ marginBottom: 20 }}>
        <Space size="large" wrap>
          <Space size={12}>
            <span className="clay-icon-box" style={{ background: CLAY.purpleTint, width: 44, height: 44, fontSize: 22 }}>📄</span>
            <Statistic title="文档数" value={stats?.documents ?? '-'} />
          </Space>
          <Space size={12}>
            <span className="clay-icon-box" style={{ background: CLAY.mustardTint, width: 44, height: 44, fontSize: 22 }}>🧩</span>
            <Statistic title="已索引片段" value={stats?.indexedChunks ?? '-'} />
          </Space>
          <Space size={12}>
            <span className="clay-icon-box" style={{ background: CLAY.mintTint, width: 44, height: 44, fontSize: 22 }}>🧠</span>
            <Statistic title="向量模型" value={stats?.embeddingModel ?? '-'} />
          </Space>
        </Space>
      </Card>

      <Card style={{ marginBottom: 16 }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input
            placeholder="输入问题或关键词，测试向量检索效果"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onPressEnter={handleSearch}
            allowClear
          />
          <Button type="primary" icon={<SearchOutlined />} loading={searching} onClick={handleSearch}>
            检索测试
          </Button>
        </Space.Compact>
        {hits.length > 0 && (
          <div style={{ marginTop: 16 }}>
            {hits.map((hit, i) => (
              <Card key={i} size="small" className="clay-card-hover" style={{ marginBottom: 10, borderRadius: 20 }}>
                <Space wrap style={{ marginBottom: 8 }}>
                  <Tag style={{ background: CLAY.purpleTint, color: CLAY.purple }}>#{i + 1}</Tag>
                  <Tag style={{ background: CLAY.base, color: CLAY.ink }}>{hit.documentTitle}</Tag>
                  <Tag style={{ background: CLAY.base, color: CLAY.ink }}>片段 {hit.seq}</Tag>
                  <Tag style={{ background: CLAY.mintTint, color: CLAY.mint }}>
                    相关度 {hit.score.toFixed(3)}
                  </Tag>
                </Space>
                <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                  {hit.content}
                </Paragraph>
              </Card>
            ))}
          </div>
        )}
      </Card>

      <Card>
        <Button icon={<ReloadOutlined />} onClick={loadData} style={{ marginBottom: 16 }}>
          刷新
        </Button>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={docs}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 篇` }}
        />
      </Card>

      <Modal
        title="导入文档"
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false)
          setBatchFiles([])
          setImportTab('single')
        }}
        footer={importTab === 'single' ? undefined : null}
        onOk={importTab === 'single' ? handleImport : undefined}
        confirmLoading={importTab === 'single' ? saving : false}
        width={680}
        destroyOnHidden
      >
        <Tabs
          activeKey={importTab}
          onChange={setImportTab}
          items={[
            {
              key: 'single',
              label: '单个导入',
              children: (
                <Form form={form} layout="vertical">
                  <Form.Item label="上传文件（.md / .txt，读取后可编辑）">
                    <Upload
                      accept=".md,.markdown,.txt"
                      maxCount={1}
                      showUploadList={false}
                      beforeUpload={readFile}
                    >
                      <Button icon={<UploadOutlined />}>选择文件</Button>
                    </Upload>
                  </Form.Item>
                  <Form.Item name="title" label="标题（留空自动取文件名/首行）">
                    <Input placeholder="文档标题" maxLength={255} />
                  </Form.Item>
                  <Form.Item name="fileName" label="来源文件名">
                    <Input placeholder="如 project-notes.md" maxLength={255} />
                  </Form.Item>
                  <Form.Item
                    name="content"
                    label="文档内容（纯文本或 Markdown）"
                    rules={[{ required: true, message: '请输入或上传文档内容' }]}
                  >
                    <Input.TextArea rows={12} placeholder="粘贴文本内容，或从上方上传文件" />
                  </Form.Item>
                </Form>
              ),
            },
            {
              key: 'batch',
              label: '批量导入',
              children: (
                <div>
                  <Space style={{ marginBottom: 16 }} direction="vertical" style={{ width: '100%' }}>
                    <Upload
                      accept=".md,.markdown,.txt"
                      multiple
                      showUploadList={false}
                      beforeUpload={collectBatchFiles}
                    >
                      <Button icon={<UploadOutlined />}>选择多个文件（.md / .txt）</Button>
                    </Upload>
                    {batchFiles.length > 0 && (
                      <Space>
                        <Button
                          type="primary"
                          loading={batchRunning}
                          onClick={handleBatchImport}
                        >
                          批量导入（{batchFiles.filter((f) => f.status !== 'ok').length} 篇待导入）
                        </Button>
                        <Button onClick={clearBatchFiles} disabled={batchRunning}>
                          清空列表
                        </Button>
                      </Space>
                    )}
                  </Space>
                  {batchFiles.length === 0 ? (
                    <Typography.Text type="secondary">
                      选择多个 .md / .txt 文件后，逐个自动分块并向量化导入。
                      因向量模型（CPU bge-m3）串行推理，导入速度约 1 篇/6 秒。
                    </Typography.Text>
                  ) : (
                    <Table
                      size="small"
                      rowKey={(_, i) => i}
                      dataSource={batchFiles.map((f, i) => ({ ...f, idx: i }))}
                      pagination={false}
                      scroll={{ y: 300 }}
                      columns={[
                        {
                          title: '文件名',
                          dataIndex: ['file', 'name'],
                          ellipsis: true,
                        },
                        {
                          title: '标题',
                          dataIndex: 'title',
                          width: 160,
                          render: (v, row) => (
                            <Input
                              size="small"
                              value={v}
                              disabled={row.status === 'ok' || row.status === 'running'}
                              onChange={(e) =>
                                setBatchFiles((prev) =>
                                  prev.map((p, idx) =>
                                    idx === row.idx ? { ...p, title: e.target.value } : p
                                  )
                                )
                              }
                            />
                          ),
                        },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          width: 130,
                          render: (v, row) => {
                            if (v === 'pending') return <Tag>待导入</Tag>
                            if (v === 'running')
                              return <Tag color="processing">导入中…</Tag>
                            if (v === 'ok')
                              return (
                                <Tag icon={<CheckCircleOutlined />} color="success">
                                  {row.msg}
                                </Tag>
                              )
                            return (
                              <Tooltip title={row.msg}>
                                <Tag icon={<CloseCircleOutlined />} color="error">
                                  失败
                                </Tag>
                              </Tooltip>
                            )
                          },
                        },
                        {
                          title: '',
                          width: 60,
                          render: (_, row) =>
                            row.status !== 'running' && (
                              <Button
                                type="link"
                                size="small"
                                danger
                                icon={<DeleteOutlined />}
                                onClick={() => removeBatchFile(row.idx)}
                              />
                            ),
                        },
                      ]}
                    />
                  )}
                </div>
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        title={viewing?.title}
        open={!!viewing}
        onCancel={() => setViewing(null)}
        footer={null}
        width={720}
      >
        {viewing && (
          <Paragraph style={{ whiteSpace: 'pre-wrap', maxHeight: '60vh', overflow: 'auto' }}>
            {viewing.content}
          </Paragraph>
        )}
      </Modal>
    </PageContainer>
  )
}
