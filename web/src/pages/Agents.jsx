import { Alert, Card, Col, Descriptions, Row, Tag, Typography } from 'antd'
import { RobotOutlined, ToolOutlined } from '@ant-design/icons'
import PageContainer from '../components/PageContainer'
import { BRAND } from '../theme'

const { Paragraph } = Typography

const agents = [
  {
    name: 'JARVIS ReAct Agent',
    type: 'ReActAgent',
    desc: '主对话智能体，通过 ReAct 推理循环调用工具完成用户请求。',
    tools: ['TaskTools.listTasks', 'TaskTools.getTask', 'TaskTools.createTask'],
    status: '在线',
  },
  {
    name: 'OpenAI 兼容模型',
    type: 'OpenAIChatModel',
    desc: '通过 OpenAI 兼容接口接入大语言模型，base-url 与模型名可在后端环境变量中配置。',
    tools: [],
    status: '在线',
  },
]

export default function Agents() {
  return (
    <PageContainer
      title="智能体管理"
      breadcrumb={[{ title: '首页', to: '/dashboard' }, { title: '智能体管理' }]}
      subTitle="已注册的 Agent 与工具"
    >
      <Alert
        type="info"
        showIcon
        message="只读概览"
        description="当前展示 JARVIS 后端已注册的智能体与工具信息，编辑/启停能力可在接入管理接口后提供。"
        style={{ marginBottom: 16 }}
      />
      <Row gutter={[16, 16]}>
        {agents.map((agent) => (
          <Col xs={24} lg={12} key={agent.name}>
            <Card>
              <Descriptions
                column={1}
                title={
                  <>
                    <RobotOutlined style={{ color: '#2f54eb', marginRight: 8 }} />
                    {agent.name}
                    <Tag color="success" style={{ marginLeft: 8 }}>
                      {agent.status}
                    </Tag>
                  </>
                }
              >
                <Descriptions.Item label="类型">{agent.type}</Descriptions.Item>
                <Descriptions.Item label="说明">
                  <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                    {agent.desc}
                  </Paragraph>
                </Descriptions.Item>
                {agent.tools.length > 0 && (
                  <Descriptions.Item label="已注册工具">
                    <ToolOutlined style={{ color: '#2f54eb' }} />
                    {agent.tools.map((t) => (
                      <Tag key={t} style={{ marginLeft: 8 }}>
                        {t}
                      </Tag>
                    ))}
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          </Col>
        ))}
      </Row>
    </PageContainer>
  )
}
