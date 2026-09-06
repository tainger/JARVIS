import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ReloadOutlined,
  SaveOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons'
import PageContainer from '../components/PageContainer'
import { profileApi } from '../api/client'
import { BRAND, CLAY, RADIUS } from '../theme'

const { TextArea } = Input
const { Text } = Typography

export default function UserProfilePage() {
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [extracting, setExtracting] = useState(false)
  const [form] = Form.useForm()

  const load = () => {
    setLoading(true)
    profileApi.get()
      .then((data) => {
        setProfile(data)
        if (data) {
          form.setFieldsValue({
            techStack: data.techStack || '',
            frequentTopics: data.frequentTopics || '',
            answerStyle: data.answerStyle || '',
            projectContext: data.projectContext || '',
          })
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  const handleSave = async () => {
    try {
      const values = await form.validateFields()
      setSaving(true)
      const updated = await profileApi.update(values)
      setProfile(updated)
      message.success('画像保存成功')
    } catch (e) {
      if (e.errorFields) return
      message.error('保存失败：' + (e.message || '未知错误'))
    } finally {
      setSaving(false)
    }
  }

  const handleExtract = async () => {
    setExtracting(true)
    message.loading({ content: '正在调用 LLM 提取画像...', key: 'extract', duration: 0 })
    try {
      const result = await profileApi.extract()
      if (result) {
        setProfile(result)
        form.setFieldsValue({
          techStack: result.techStack || '',
          frequentTopics: result.frequentTopics || '',
          answerStyle: result.answerStyle || '',
          projectContext: result.projectContext || '',
        })
        message.success({ content: '画像提取完成', key: 'extract' })
      } else {
        message.warning({ content: '提取完成但无结果（可能对话历史不足）', key: 'extract' })
      }
    } catch (e) {
      message.error({ content: '提取失败：' + (e.message || '未知错误'), key: 'extract' })
    } finally {
      setExtracting(false)
    }
  }

  return (
    <PageContainer
      title="个人画像"
      emoji="📋"
      breadcrumb={[{ title: '首页', to: '/' }, { title: '个人画像' }]}
      description="Agent 根据你的画像自动调整回答风格和深度。每 5 次对话自动提取一次，也可手动编辑或重新提取。"
      extra={
        <ReloadOutlined onClick={load} style={{ fontSize: 18, cursor: 'pointer', color: CLAY.inkSoft }} />
      }
    >
      {loading ? (
        <Spin size="large" style={{ display: 'block', padding: 60 }} />
      ) : (
        <>
          {/* 统计信息 */}
          {profile && (
            <Card style={{ borderRadius: RADIUS.lg, marginBottom: 16, background: CLAY.purpleTint }}>
              <Descriptions column={3} size="small">
                <Descriptions.Item label="对话次数">
                  <Tag color="purple" style={{ fontSize: 14, padding: '2px 12px' }}>
                    {profile.conversationCount ?? 0}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label="最近更新">
                  {profile.updatedAt ? new Date(profile.updatedAt).toLocaleString('zh-CN') : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="下次自动提取">
                  {profile.conversationCount != null
                    ? `还有 ${5 - ((profile.conversationCount + 1) % 5)} 次对话`
                    : '-'}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          )}

          {/* 编辑表单 */}
          <Card
            title={
              <Space>
                <UserOutlined style={{ color: BRAND.primary }} />
                画像信息
              </Space>
            }
            style={{ borderRadius: RADIUS.lg, marginBottom: 16 }}
            extra={
              <Space>
                <Button
                  icon={<ThunderboltOutlined />}
                  onClick={handleExtract}
                  loading={extracting}
                >
                  重新提取
                </Button>
                <Button
                  type="primary"
                  icon={<SaveOutlined />}
                  onClick={handleSave}
                  loading={saving}
                >
                  保存修改
                </Button>
              </Space>
            }
          >
            <Form form={form} layout="vertical">
              <Form.Item
                name="techStack"
                label="技术栈偏好"
                tooltip="Agent 会根据技术栈选择示例语言（如 Java → Spring 示例，React → JSX 示例）"
              >
                <TextArea
                  rows={2}
                  placeholder="如：Java, Spring Boot, MyBatis, MySQL"
                />
              </Form.Item>

              <Form.Item
                name="frequentTopics"
                label="常问主题"
                tooltip="Agent 会对这些主题提供更深入的回答"
              >
                <TextArea
                  rows={2}
                  placeholder="如：Nacos架构, 分布式事务, 知识库管理"
                />
              </Form.Item>

              <Form.Item
                name="answerStyle"
                label="回答风格偏好"
                tooltip="Agent 会调整回答的详细程度和格式"
              >
                <TextArea
                  rows={2}
                  placeholder="如：简洁代码示例，附原理解释"
                />
              </Form.Item>

              <Form.Item
                name="projectContext"
                label="项目上下文"
                tooltip="Agent 会结合项目背景给出更有针对性的回答"
              >
                <TextArea
                  rows={2}
                  placeholder="如：正在分析 Nacos 2.x 源码，开发运维工具"
                />
              </Form.Item>
            </Form>
          </Card>

          {/* LLM 生成的摘要 */}
          {profile?.rawSummary && (
            <Card
              title="LLM 画像摘要"
              style={{ borderRadius: RADIUS.lg, background: CLAY.coralTint }}
            >
              <Text style={{ color: CLAY.ink, fontSize: 15 }}>
                {profile.rawSummary}
              </Text>
            </Card>
          )}

          {!profile && (
            <Card style={{ borderRadius: RADIUS.lg, textAlign: 'center', padding: 40 }}>
              <Text type="secondary" style={{ fontSize: 15 }}>
                还没有用户画像。开始对话后，每 5 次对话系统会自动提取画像。
                也可以点击右上角"重新提取"手动触发。
              </Text>
            </Card>
          )}
        </>
      )}
    </PageContainer>
  )
}
