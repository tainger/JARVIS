import { useEffect, useState } from 'react'
import { Alert, Card, Col, Descriptions, Row, Spin, Tag, Typography } from 'antd'
import { RobotOutlined, ToolOutlined, ApiOutlined } from '@ant-design/icons'
import PageContainer from '../components/PageContainer'
import { BRAND, CLAY } from '../theme'
import { mcpApi } from '../api/client'

const { Paragraph, Text } = Typography

const agents = [
  {
    name: 'JARVIS ReAct Agent',
    type: 'ReActAgent',
    desc: '主对话智能体，通过 ReAct 推理循环调用工具完成用户请求。已注册的内置工具与 MCP 工具均可被自动调用。',
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
  const [mcp, setMcp] = useState(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading(true)
    mcpApi
      .overview()
      .then(setMcp)
      .catch(() => setMcp(null))
      .finally(() => setLoading(false))
  }, [])

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
        description="当前展示 JARVIS 后端已注册的智能体、MCP server 与工具信息。MCP server 通过 application.properties 声明式配置。"
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
                    <RobotOutlined style={{ color: BRAND.primary, marginRight: 8 }} />
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
              </Descriptions>
            </Card>
          </Col>
        ))}
      </Row>

      <Card
        style={{ marginTop: 16 }}
        title={
          <>
            <ApiOutlined style={{ color: BRAND.primary, marginRight: 8 }} />
            MCP Servers（Model Context Protocol）
          </>
        }
      >
        <Spin spinning={loading}>
          {!mcp ? (
            <Text type="secondary">无法连接后端 MCP 接口</Text>
          ) : mcp.configuredCount === 0 ? (
            <Text type="secondary">
              未配置任何 MCP server。可在 application.properties 中取消注释
              <Text code>agentscope.mcp.servers[*]</Text>
              配置项启用。
            </Text>
          ) : (
            <Row gutter={[16, 16]}>
              {mcp.configuredServers.map((s) => (
                <Col xs={24} lg={12} key={s.name}>
                  <Descriptions column={1} size="small" title={s.name}>
                    <Descriptions.Item label="传输">
                      <Tag color="blue">{s.transport}</Tag>
                    </Descriptions.Item>
                    {s.url && (
                      <Descriptions.Item label="URL">
                        <Text code copyable>
                          {s.url}
                        </Text>
                      </Descriptions.Item>
                    )}
                    {s.command && (
                      <Descriptions.Item label="启动命令">
                        <Text code style={{ wordBreak: 'break-all' }}>
                          {s.command.join(' ')}
                        </Text>
                      </Descriptions.Item>
                    )}
                  </Descriptions>
                </Col>
              ))}
            </Row>
          )}
        </Spin>

        {mcp && mcp.activeToolCount > 0 && (
          <Descriptions
            column={1}
            size="small"
            style={{ marginTop: 16 }}
            title={
              <>
                <ToolOutlined style={{ color: BRAND.primary, marginRight: 8 }} />
                Toolkit 中所有可用工具（{mcp.activeToolCount}）
              </>
            }
          >
            <Descriptions.Item label="工具列表">
              {mcp.activeTools.map((t) => (
                <Tag key={t} style={{ marginBottom: 4 }}>
                  {t}
                </Tag>
              ))}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>
    </PageContainer>
  )
}
