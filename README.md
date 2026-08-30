# JARVIS

> DeepSeek Harness 风格的自主智能体（Agent）后台，基于 Spring Boot 4 + H2 + MyBatis + AgentScope，前端使用 React + Ant Design。

## 特性

- 🤖 **AgentScope ReAct Agent**：Tool calling（任务库 + MCP + 知识库搜索）+ DeepSeek 流式响应
- 🧩 **MCP 集成**：stdio / SSE / streamableHttp 三种传输，声明式注册
- 📚 **简易 RAG 知识库**：
  - 本地 Ollama（默认 bge-m3，1024 维）段落感知分块 + 向量化
  - H2 knowledge_document / knowledge_chunk 持久化
  - 内存 cosine Top-K 相似度检索
  - **双入口**：对话前自动 prompt 注入 + `knowledgeSearch` Agent 工具
- 🖥️ **管理后台**：仪表盘 / AI 对话（SSE 流式）/ 任务 / 智能体 / 知识库（前端上传 .md/.txt）
- 🐳 **一键 Docker Compose**：前后端 + Ollama，自动拉 bge-m3、named volume 持久化

## 快速开始

### 方式 A：Docker 部署（推荐演示/生产）

```bash
cp deploy/.env.example .env     # 至少填 AGENTSCOPE_API_KEY
./deploy.sh up                  # 构建 + 启动，首次需要几分钟（编译+拉 bge-m3）
./deploy.sh check               # 验证就绪后：
open http://localhost:8080      # 访问前端
```

### 方式 B：本地开发（Java + Node）

详见 [docs/setup-guide.md](docs/setup-guide.md)。简要：

1. 本机安装 JDK 17+、Node 20+、Ollama
2. `ollama pull bge-m3`
3. 设置 3 个环境变量：`AGENTSCOPE_API_KEY / AGENTSCOPE_BASE_URL / AGENTSCOPE_MODEL`
4. 后端 `./mvnw spring-boot:run`（8080）
5. 前端 `cd web && npm install && npm run dev`（5173）
6. 访问 http://localhost:5173/

## 文档

| 文档 | 说明 |
|---|---|
| [docs/setup-guide.md](docs/setup-guide.md) | **完整启动与配置指南**（环境 → MySQL → Ollama & bge-m3 详解 → 启动 → RAG 使用 → Docker 部署）|
| [docs/rag-design.md](docs/rag-design.md) | **RAG 设计文档**（工作流程与原理 → 缺点 → Phase 1~3 演进路线）|
| [docs/llm-wiki-research.md](docs/llm-wiki-research.md) | LLM Wiki / 知识编译技术调研（M1~M3 选型）|
| [docs/java-dsh-design.md](docs/java-dsh-design.md) | JARVIS = DeepSeek Harness（DSH）Java 版 架构设计稿（M1~M5）|

## 目录

```
├── docker-compose.yml        Compose 编排（前后端 + Ollama + 可选 WebUI）
├── deploy.sh                 一键脚本：up/down/check/backup/logs/ps
├── deploy/
│   ├── Dockerfile.backend    后端：Maven 构建 + JRE 运行
│   ├── Dockerfile.frontend   前端：Vite build + Nginx 反代
│   ├── nginx.conf.template   Nginx 模板（/api 反代 + SSE 无缓冲）
│   └── .env.example          环境变量模板
├── src/                      后端 Java 代码（Spring Boot 4 / MyBatis / MySQL / AgentScope / RAG）
│   └── main/java/com/example/jarvis/rag/   RAG 核心（Ollama 客户端 + KnowledgeService）
├── web/                      前端 React + Ant Design（Vite）
├── docs/                     设计 & 指南文档
└── log/                      Logback 落盘日志（开发模式）
```
