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
  Tag,
  Typography,
  Upload,
} from 'antd'
import {
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
        onOk={handleImport}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        width={640}
        destroyOnHidden
      >
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
