# JARVIS 项目启动与配置指南

> 适用范围：当前主分支（Spring Boot 4 + H2 + MyBatis + AgentScope + Ollama RAG + React/Vite 前端）

---

## 1. 启动总览

```
┌─────────────────────────────────────────────────────────────┐
│                       本机环境准备                            │
│   ① Java 17+   ② Maven（项目自带 wrapper ./mvnw）            │
│   ③ Node 20+   ④ Ollama（含 bge-m3 向量模型）                │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌──────────────────────────────────────────────────────────────┐
│                       配置与启动                              │
│                                                              │
│  后端 (8080)：                                               │
│    环境变量注入 API Key  →  ./mvnw spring-boot:run           │
│                                                              │
│  前端 (5173)：                                               │
│    npm install  →  cd web && npm run dev                     │
│    Vite 代理 /api → http://localhost:8080                    │
│                                                              │
│  登录：任意用户名 → 跳 http://localhost:5173/ 主控制台        │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 前置环境

| 工具 | 要求 | 检查命令 |
|---|---|---|
| **Java** | JDK 17+ | `java -version` |
| **Node** | v20+ | `node -v`（项目已测 v25.8.0）|
| **Maven** | 项目自带 wrapper，无需单独装 | 根目录存在 `./mvnw` |
| **Ollama** | ≥ v0.23 | `ollama --version`；**启动后 11434 必须监听** |

### 2.1 安装 Ollama

macOS（二选一）：

```bash
# 方式 A：官方 App（推荐）
# 从 https://ollama.com/download 获取 Ollama.app，放到 ~/Applications 后双击启动，
# 状态栏出现羊驼图标后端口 11434 自动监听。

# 方式 B：brew 命令行版
brew install --cask ollama
ollama serve          # 前台运行，或放 launchd 常驻
```

安装后验证：

```bash
curl http://127.0.0.1:11434/api/version
# 期望输出：{"version":"0.xx.x"}
```

---

## 3. 向量模型 bge-m3 配置（重点）

> **为什么用 bge-m3**：中文/英文/多语种都表现好，支持长文本，Ollama 直接拉，零新增 Java 依赖。
>
> 向量维度：**1024**。后端走 `GET /api/knowledge/stats` 可见 `embeddingModel: bge-m3`。

### 3.1 拉取模型

```bash
ollama pull bge-m3
```

下载期间会看到 `pulling manifest → downloading → verifying → writing manifest → success`，
成功后验证：

```bash
ollama list
# 期望输出一行：bge-m3:latest    ...（~500MB~1GB）

# 再测一次真实 embedding
curl http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["你好世界"]}' | jq '.embeddings[0] | length'
# 期望输出：1024
```

### 3.2 后端配置（application.properties）

默认值已经能跑（连本地 Ollama、用 bge-m3），一般不改也能起：

```properties
# ---------- RAG 知识库（检索核心 + Ollama 向量化） ----------
# 前提：本机运行 ollama serve，并已 ollama pull bge-m3
rag.embedding.base-url=${RAG_EMBEDDING_BASE_URL:http://127.0.0.1:11434}
rag.embedding.model=${RAG_EMBEDDING_MODEL:bge-m3}
rag.embedding.timeout-seconds=60

# 检索返回片段数 / 相似度下限（cosine，范围约 [-1,1]；文档少时可降到 0.2）
rag.retrieval.top-k=${RAG_TOP_K:4}
rag.retrieval.min-score=0.3

# 分块：段落感知合并的目标大小；超过硬上限的段落按句号切
rag.chunk.max-chars=800
rag.chunk.hard-limit=1200
```

### 3.3 用环境变量覆盖（生产/自定义场景）

不想改配置文件就用环境变量覆盖默认值，和 DeepSeek Key 的注入方式一致：

```bash
# 示例：Ollama 跑在另一台机器，或非默认端口
export RAG_EMBEDDING_BASE_URL=http://10.0.0.12:11434
export RAG_EMBEDDING_MODEL=bge-m3          # 也可换 nomic-embed-text / mxbai-embed-large 等
export RAG_TOP_K=6                         # 返回更多片段

# 然后按 §4 启动后端
```

### 3.4 常见报错 & 排查

| 现象 | 根因 | 解决 |
|---|---|---|
| 启动 WARN: `Ollama embedding 请求失败：HTTP 404 model not found` | 没 `ollama pull bge-m3` | `ollama pull bge-m3` 再重启后端 |
| 启动 WARN: `无法连接 Ollama (http://127.0.0.1:11434)` | `ollama serve` 没起 | `open -a Ollama` 或 `ollama serve` |
| `ollama pull bge-m3` 报 **permission denied** 写 `~/.ollama/...partial` | 从受限沙箱子进程后台拉起的 Ollama 没有用户目录写权限 | `pkill -x ollama`，然后从系统菜单栏或正常 shell 启动 Ollama App 再 pull |
| 导入"文档过大"提示 | 单文档 ≥ 2 MB 文本 | 拆成几个文件分别导入；或改 Controller 的上限 |
| 导入时间长、对话卡顿 | 首次导入/首次检索 Ollama 要把模型从磁盘加载到内存 | 冷机预热一次（`POST /knowledge/search` 随便查一句）即可 |

> **本台机器特别注意**：Trae 的终端沙箱对后台拉起的子进程做了写权限限制。
> 如果发现沙箱里的 `ollama serve` 无法拉模型，做法：
> 1. `pkill -x ollama` 清掉
> 2. 正常启动 `~/Applications/Ollama.app`（双击 或 `open ~/Applications/Ollama.app`）
> 3. 在普通终端里 `ollama pull bge-m3`
> 4. 后端重启后观察日志不再出现 permission denied 即可

---

## 4. 启动后端

### 4.1 必须注入的环境变量

`sk-demo-key` 是占位符，会导致模型接口 401，**一定要用真实 Key 覆盖**：

```bash
export AGENTSCOPE_API_KEY=sk-your-real-deepseek-key
export AGENTSCOPE_BASE_URL=https://api.deepseek.com/v1
export AGENTSCOPE_MODEL=deepseek-chat

# 可选（不写时用默认值）
# export RAG_EMBEDDING_BASE_URL=http://127.0.0.1:11434
# export RAG_EMBEDDING_MODEL=bge-m3
# export RAG_TOP_K=4
```

### 4.2 编译 & 启动

```bash
# 项目根目录
cd JARVIS

# 首次或拉代码后（编译 + 跑单元测试）
./mvnw clean package -DskipTests

# 启动（Spring Boot 嵌入式 Tomcat，端口 8080）
./mvnw spring-boot:run
```

启动成功标志：

```
Started JarvisApplication in X.XXX seconds
知识库内存索引已加载：N 个向量块   # N=0 是正常（首次没导入文档）
```

### 4.3 验证后端

```bash
# 健康
curl http://localhost:8080/api/knowledge/stats
# → {"documents":0,"indexedChunks":0,"embeddingModel":"bge-m3"}

# 任务库（校验 H2 + MyBatis）
curl http://localhost:8080/api/tasks
# → [] 或已有任务列表

# H2 控制台（浏览器打开）
open http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:jarvisdb    用户: sa    密码: (空)

# 知识库快速导入一条（为测试 RAG 效果）
curl -X POST http://localhost:8080/api/knowledge/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "测试文档",
    "content": "公司餐补：早餐 30 元、午餐 50 元、晚餐 60 元。按出差天数自动计算，无需发票。"
  }'
# → {"id":1,"chunkCount":1,...}
```

### 4.4 日志位置

Logback 已配置文件落盘 + 滚动策略：

```
log/
├── jarvis-info.log     # INFO+ 业务日志
├── jarvis-error.log    # ERROR 专用
├── jarvis-all.log      # 全级别（含 MyBatis SQL DEBUG）
└── archived/           # gzip 历史滚动
```

观察 RAG 是否生效：聊天命中时会看到 `INFO RAG 注入：命中知识库片段`。

---

## 5. 启动前端

```bash
cd JARVIS/web

# 首次/依赖变更时
npm install

# 启动 Vite dev server（端口 5173，/api 自动代理到 8080）
npm run dev
```

浏览器访问 **http://localhost:5173/**，首次会跳到 `/login`：

- 用户名：随意填（admin / 你的名字都行），当前是前端 localStorage 模拟登录
- 登录后左侧菜单：仪表盘 / **AI 对话** / 任务管理 / 用户管理 / 智能体管理 / **知识库**

### 5.1 前端路由一览

| 路径 | 页面 | 说明 |
|---|---|---|
| `/dashboard` | 仪表盘 | 入口概览 |
| `/chat` | AI 对话 | 调用 `/api/agent/chat/stream`（SSE 流式 + **命中知识库自动注入上下文**）|
| `/tasks` | 任务管理 | CRUD H2 task 表 |
| `/knowledge` | **知识库** | 上传 .md/.txt、粘贴导入、删除、检索测试（§6）|
| `/agents` | 智能体管理 | 查看已注册工具 |

---

## 6. RAG 知识库使用流程

```
① 打开 http://localhost:5173/knowledge
   ↓
② 点击【导入文档】
   ├─ 直接粘贴 Markdown/纯文本；或
   └─ 选择 .md / .txt 文件 → 前端读取成文本后可二次编辑
   ↓
③ 后端分块（段落感知合并，目标 800 字符，硬切 1200 字符）
   ↓
④ 批量调 Ollama bge-m3 向量化（每块 → 1024 维 float[] → JSON 存 H2 knowledge_chunk）
   ↓
⑤ 内存索引写时复制刷新
   ↓
⑥ 使用：
   ├─ 方式 A（自动）：聊天页面问问题，后端先查 Top-K 命中 → 拼进 prompt
   └─ 方式 B（主动工具调用）：ReAct agent 决定调用 knowledge_search 工具，
         返回的结果再让模型组织回答（回答引用来源更灵活）
```

验证效果：§4.3 导入那条"餐补 30/50/60"后，在 AI 对话里问：

> 出差一天吃饭能报多少钱？

期望在回复里看到明确的 30/50/60 数字，并说明"按出差天数自动计算"。

---

## 7. 快速自检清单（上线前必查）

| # | 检查项 | 命令/位置 |
|---|---|---|
| 1 | Java 17 可用 | `java -version` |
| 2 | Node ≥ 20 | `node -v` |
| 3 | Ollama 监听 11434 | `curl http://127.0.0.1:11434/api/version` |
| 4 | bge-m3 已下载 | `ollama list \| grep bge-m3` |
| 5 | 向量维度 1024 | `POST /api/embed` 查长度 |
| 6 | DeepSeek Key 非占位符 | `echo $AGENTSCOPE_API_KEY` 开头是 `sk-` 且不是 `sk-demo-key` |
| 7 | 后端 8080 起来 | `curl http://localhost:8080/api/knowledge/stats` 返回 200 |
| 8 | 前端 5173 起来 | 浏览器访问 http://localhost:5173/ |
| 9 | 一条测试文档 | `/knowledge` 能看到导入的文档 |
| 10 | 对话能引用知识库 | 问"餐补标准"，回复含"30/50/60"等具体数字 |

---

## 8. 关闭服务

```bash
# 前端：Ctrl+C 停 dev server，或：
lsof -ti:5173 | xargs kill

# 后端：
lsof -ti:8080 | xargs kill

# Ollama（如启动方式是前台 ollama serve）：
pkill -x ollama
# Ollama App 方式在状态栏图标 → Quit 即可
```

---

## 9. 配置文件索引

| 配置点 | 位置 |
|---|---|
| 数据源 / H2 / MyBatis / RAG / AgentScope / MCP | `src/main/resources/application.properties` |
| Logback 滚动策略 | `src/main/resources/logback-spring.xml` |
| H2 表结构（含 `knowledge_*` 两张表）| `src/main/resources/schema.sql` |
| Vite 端口 / `/api` 代理目标 | `web/vite.config.js` |
| Axios 封装 / 知识库 API 封装 | `web/src/api/client.js` |
| 全局错误格式（401/429/500 JSON）| `controller/GlobalExceptionHandler.java` |
