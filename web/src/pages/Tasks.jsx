import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { taskApi } from '../api/client'

const { Title } = Typography

export default function Tasks() {
  const { message } = App.useApp()
  const [tasks, setTasks] = useState([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState(undefined)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const loadTasks = useCallback(() => {
    setLoading(true)
    taskApi
      .list()
      .then(setTasks)
      .catch(() => message.error('加载任务失败，请检查后端服务'))
      .finally(() => setLoading(false))
  }, [message])

  useEffect(() => {
    loadTasks()
  }, [loadTasks])

  const filtered = useMemo(
    () =>
      tasks.filter((t) => {
        const matchKw =
          !keyword ||
          t.title?.toLowerCase().includes(keyword.toLowerCase()) ||
          t.description?.toLowerCase().includes(keyword.toLowerCase())
        const matchStatus =
          status === undefined || t.completed === (status === 'completed')
        return matchKw && matchStatus
      }),
    [tasks, keyword, status],
  )

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (record) => {
    setEditing(record)
    form.setFieldsValue(record)
    setModalOpen(true)
  }

  const handleSubmit = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      if (editing) {
        await taskApi.update(editing.id, values)
        message.success('任务已更新')
      } else {
        await taskApi.create(values)
        message.success('任务已创建')
      }
      setModalOpen(false)
      loadTasks()
    } catch (e) {
      message.error('保存失败：' + (e?.response?.data?.message || e.message))
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id) => {
    try {
      await taskApi.remove(id)
      message.success('任务已删除')
      loadTasks()
    } catch (e) {
      message.error('删除失败：' + (e?.response?.data?.message || e.message))
    }
  }

  const handleToggle = async (record, checked) => {
    try {
      await taskApi.update(record.id, { ...record, completed: checked })
      message.success(checked ? '任务已完成' : '任务已标记为进行中')
      loadTasks()
    } catch (e) {
      message.error('更新失败')
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '标题', dataIndex: 'title' },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v) => v || '-' },
    {
      title: '状态',
      dataIndex: 'completed',
      width: 120,
      render: (v) =>
        v ? <Tag color="success">已完成</Tag> : <Tag color="warning">进行中</Tag>,
    },
    {
      title: '完成状态',
      dataIndex: 'completed',
      width: 100,
      render: (v, record) => (
        <Switch
          checked={v}
          size="small"
          checkedChildren="完成"
          unCheckedChildren="进行"
          onChange={(checked) => handleToggle(record, checked)}
        />
      ),
    },
    {
      title: '操作',
      width: 140,
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEdit(record)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定删除该任务？"
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
    <div>
      <Title level={4} style={{ marginTop: 0 }}>
        任务管理
      </Title>
      <Card>
        <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
          <Space>
            <Input.Search
              placeholder="搜索标题或描述"
              allowClear
              style={{ width: 240 }}
              onSearch={setKeyword}
            />
            <Select
              placeholder="状态筛选"
              allowClear
              style={{ width: 140 }}
              value={status}
              onChange={setStatus}
              options={[
                { value: 'completed', label: '已完成' },
                { value: 'pending', label: '进行中' },
              ]}
            />
            <Button icon={<ReloadOutlined />} onClick={loadTasks}>
              刷新
            </Button>
          </Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建任务
          </Button>
        </Space>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        />
      </Card>

      <Modal
        title={editing ? '编辑任务' : '新建任务'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" initialValues={{ completed: false }}>
          <Form.Item
            name="title"
            label="标题"
            rules={[{ required: true, message: '请输入任务标题' }]}
          >
            <Input placeholder="任务标题" maxLength={255} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="任务描述（可选）" maxLength={255} rows={3} />
          </Form.Item>
          <Form.Item name="completed" label="是否完成" valuePropName="checked">
            <Switch checkedChildren="完成" unCheckedChildren="进行中" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
