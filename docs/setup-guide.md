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
# 超时需覆盖模型冷加载耗时；keep_alive 让模型常驻内存，避免反复冷加载
rag.embedding.timeout-seconds=120
rag.embedding.keep-alive=60m
# 单次 HTTP 请求最大输入条数：CPU 推理约 6s/条，批太大会撞超时墙
rag.embedding.batch-size=4

# 混合检索：score = 0.75*向量cosine + 0.25*词面重合（中文 bigram + 英文词元）
# min-score 仅用于"对话注入"过滤；搜索接口 /search 永远返回 Top-K 不截断
rag.retrieval.top-k=${RAG_TOP_K:4}
rag.retrieval.min-score=0.25

# 分块：Markdown 标题感知 + 面包屑前缀（"手册 > 报销制度"）+ 相邻块重叠；
# 导入时自动剥离 HTML（简历类富文本导出的 <div>/<img> 噪声）
rag.chunk.max-chars=500
rag.chunk.hard-limit=800
rag.chunk.overlap-chars=80
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

### 4.0 本地 MySQL（业务库）

后端业务数据（task / 知识库）已从 H2 迁移到 **MySQL**，本地开发需要先准备一个 MySQL 8+：

```bash
# ① 安装（本机已装 9.2.0，跳过）：
brew install mysql

# ② 启动。推荐 launchd 常驻（开机自启；沙箱受限时在用户自己的终端执行）：
brew services start mysql
#    或临时前台跑：
mysql.server start

# ③ 建库建用户（root 本地空密码，首次执行）
mysql -uroot -e "
CREATE DATABASE IF NOT EXISTS jarvis CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'jarvis'@'127.0.0.1' IDENTIFIED BY 'jarvis123';
CREATE USER IF NOT EXISTS 'jarvis'@'localhost' IDENTIFIED BY 'jarvis123';
GRANT ALL PRIVILEGES ON jarvis.* TO 'jarvis'@'127.0.0.1';
GRANT ALL PRIVILEGES ON jarvis.* TO 'jarvis'@'localhost';
FLUSH PRIVILEGES;"

# ④ 验证
mysql -h127.0.0.1 -ujarvis -pjarvis123 jarvis -e "SELECT 1"
```

- 表结构由后端启动时 `schema.sql`（MySQL 方言，幂等建表）自动创建，无需手工执行
- 连接参数默认 `127.0.0.1:3306/jarvis`，可用环境变量覆盖（见 §4.1）
- Docker 部署不用装本地 MySQL，compose 自带 `mysql:8.4` 服务（§9）

> **本机当前状态说明**：因 Trae 沙箱限制 launchctl 与 `/usr/local/var` 写入，
> 本台机器的 mysqld 是以 `--datadir=/tmp/jarvis-mysql-data --skip-log-bin` 前台进程方式运行的。
> /tmp 数据目录**重启机器会丢**，丢后处理：`mysql.server start`（或重跑上面的 ②③）→ 重新导入
> `docs/kb-seeds/` 的三篇种子文档即可。用户终端不受沙箱限制，建议改用 `brew services start mysql` 常驻。

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
# 数据库（默认 127.0.0.1:3306/jarvis + jarvis/jarvis123，本地开发一般不用动）
# export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/jarvis?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
# export MYSQL_USERNAME=jarvis
# export MYSQL_PASSWORD=jarvis123
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

# 任务库（校验 MySQL + MyBatis）
curl http://localhost:8080/api/tasks
# → [] 或已有任务列表

# 直连 MySQL 看表（应有三张表）
mysql -h127.0.0.1 -ujarvis -pjarvis123 jarvis -e "SHOW TABLES"
# knowledge_chunk  knowledge_document  task

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
| `/eval` | **评测中心** | RAG 评测历史/趋势/用例明细/候选池 triage（§7）|
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
③ 后端清洗（剥离 HTML）→ 标题感知分块（面包屑前缀 + 目标 500 字符、硬切 800、相邻块重叠 80）
   ↓
④ 分批调 Ollama bge-m3 向量化（每批 4 条 → 1024 维 float[] → JSON 存 H2 knowledge_chunk）
   ↓
⑤ 内存索引写时复制刷新
   ↓
⑥ 使用：
   ├─ 方式 A（自动）：聊天页面问问题，后端先查混合检索 Top-K（0.75×向量 + 0.25×词面）→ 拼进 prompt
   └─ 方式 B（主动工具调用）：ReAct agent 决定调用 knowledge_search 工具，
         返回的结果再让模型组织回答（回答引用来源更灵活）
```

验证效果：§4.3 导入那条"餐补 30/50/60"后，在 AI 对话里问：

> 出差一天吃饭能报多少钱？

期望在回复里看到明确的 30/50/60 数字，并说明"按出差天数自动计算"。

**数据备份与恢复**：知识库数据持久化在 MySQL `jarvis` 库（见 §4.0）。
种子文档保存在 `docs/kb-seeds/`（`team-handbook.md`、`resume.md`、`llm-wiki-research.md`），数据丢失时可通过页面重新导入或：

```bash
curl -s -X POST http://localhost:8080/api/knowledge/documents \
  -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json;print(json.dumps({'title':'贾志远的简历','fileName':'resume.md','content':open('docs/kb-seeds/resume.md').read()},ensure_ascii=False))")"
```

**注意**：停止后端务必用优雅方式（Ctrl+C 或 `kill <pid>`），`kill -9` 可能丢失未落盘的事务。
MySQL 的数据目录见 §4.0（本机 /tmp/jarvis-mysql-data，重启机器后需重新初始化并导入种子文档）。

---

## 7. RAG 评测系统

> 设计与决策详见 [openspec/changes/add-rag-eval-system](../openspec/changes/add-rag-eval-system/proposal.md)；
> 指标基线见 [docs/rag-design.md](rag-design.md) §四基线章节。

### 7.1 一键跑评测

```bash
./run-eval.sh          # 检索层（零 token，需本地 Ollama + 后端 8080 运行中）
./run-eval.sh --llm    # 追加生成层（走真实 DeepSeek chat 链路 + LLM-as-judge 忠实度评分）
```

每次运行自动归档到 `docs/eval/history/<UTC日期>-<git短哈希>-<序号>/`（`summary.json` + `report.md`），
报告自动与上一次同 suite 归档 diff（±0.03 显著标记）。指标跌破基线时测试失败，脚本非零退出。

### 7.2 触发机制（可选）

```bash
./deploy/hooks/install.sh   # 安装 git pre-push 钩子
```

改动 `rag/**`、`application.properties`、`schema.sql`、标注集或 eval 包后 push 会先跑检索层评测，
失败则拒绝 push（`git push --no-verify` 可逃生）。CI 见 `.github/workflows/eval.yml`（paths 触发，
只跑检索层，归档上传为 artifact）。

### 7.3 候选池流水线（坏 case 收集）

1. **入池**：聊天页每条 AI 回答右下角点 **👎 回答不满意**，填一句备注提交；
   或直接 `POST /api/knowledge/eval/candidates`。
2. **triage**：前端 **评测中心**（`/eval`）底部候选池区块，填查询类型 / 期望文档 / 关键词后点 **转正**，
   用例追加写入标注集 `src/test/resources/rag-eval-cases.json`；不合适的点 **丢弃**。

### 7.4 评测中心（/eval）

- **指标卡**：最新一次检索层运行的 Recall@4 / MRR / 误注入率 / 注入召回与达标状态；
- **趋势图**：Recall@4 / MRR 随运行次数的变化（≥2 次归档后展示）；
- **评测历史**：点任意行打开详情抽屉——与上次的指标 diff 表 + 用例明细表（按类型/结果筛选，未命中用例展开查看 Top-K 命中详情）；
- **候选池 triage**：见 §7.3。

历史 API（只读、登录鉴权）：`GET /api/knowledge/eval/history`、`GET .../history/{runId}`。
Docker 部署时归档目录通过 `eval_history` 卷持久化（`/app/docs/eval/history`），容器重建不丢历史。

---

## 9. Docker 部署（推荐生产/演示/一键起）

部署拓扑（一键 docker compose up）：

```
                            主机浏览器
                                │
                                ▼  8080（可在 .env 改）
                     ┌─────────────────────┐
                     │   jarvis-frontend   │  Nginx：托管静态 SPA + /api 反代
                     │   (nginx:1.27-alpine)│  + SSE 流式无缓冲 + SPA fallback
                     └──────────┬──────────┘
                                │ /api  →  http://backend:8080
                                ▼
                     ┌─────────────────────┐
                     │   jarvis-backend    │  Spring Boot 4 / JRE 17
                     │   (自建，~500MB)    │  RAG_EMBEDDING_BASE_URL=http://ollama:11434
                     └──────────┬──────────┘
                                │ 向量请求
                                ▼  11434（容器内，可按 .env OLLAMA_PORT 映射到主机）
                     ┌─────────────────────┐
                     │       ollama        │  bge-m3 等模型存 named volume
                     │   (ollama/ollama)   │  ollama-init 首次自动 pull bge-m3
                     └─────────────────────┘
```

### 9.1 目录结构（已新增）

```
JARVIS/
├── docker-compose.yml                 # Compose 主文件，放根目录（context = ..）
├── deploy.sh                          # 一键：up / down / check / backup ...
└── deploy/
    ├── Dockerfile.backend             # 后端：maven 构建 → jre 运行
    ├── Dockerfile.frontend            # 前端：node 构建 → nginx
    ├── nginx.conf.template            # /api 反代 + SSE streaming 配置
    └── .env.example                   # 环境变量模板
```

### 9.2 三步启动

```bash
cd JARVIS

# ① 复制配置模板，至少填 AGENTSCOPE_API_KEY
cp deploy/.env.example .env
# 编辑：vim .env
#  至少改这一行：AGENTSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxx

# ② 一键构建 & 启动（首次需要编译前端/后端 + 拉 bge-m3，几分钟）
./deploy.sh up
# 或：docker compose up --build -d

# ③ 打开浏览器：http://localhost:8080
```

启动后可以用：

```bash
./deploy.sh check     # 端到端健康检查：前端 200 / 后端 200 / 知识库 stats
./deploy.sh logs -f   # 跟随日志（等价 docker compose logs -f）
./deploy.sh backup    # 把知识库日志 + Ollama 模型 gzip 到 backup/
./deploy.sh down      # 停容器（默认保留 named volume，下次不用重拉模型）
```

### 9.3 端口一览（都能在 `.env` 覆盖）

| 端口变量 | 默认值 | 服务 | 暴露到公网？ |
|---|---|---|---|
| `JARVIS_FRONTEND_PORT` | 8080 | Nginx 前端 + `/api` 入口 | **是**（唯一入口）|
| `JARVIS_BACKEND_PORT`  | 18080 | Spring Boot 直连端口 | 一般否（走前端反代）|
| `OLLAMA_PORT`          | 11434 | Ollama 管理端口 | 一般否（局域网才开）|
| `OLLAMA_WEBUI_PORT`    | 3000 | Ollama WebUI（可选）| 一般否 |

### 9.4 bge-m3 在 Docker 中的部署细节

Docker 里 bge-m3 不像本机要手动 `ollama pull`，**compose 做了编排自动化**：

1. **ollama 服务**（`ollama/ollama:0.23`）
   - volume `ollama_data:/root/.ollama` 存 blobs，容器重建不重下
   - healthcheck 用 `ollama list` 判定"已就绪"
2. **ollama-init 一次性容器**
   - depends_on `ollama: service_healthy`
   - 启动后 curl `http://ollama:11434/api/pull` 流式=false，一直等到返回成功
   - 失败 / 拉到一半容器退出也无所谓，下一次 `compose up` 会接着拉
3. **后端** depends_on `ollama-init: service_completed_successfully`
   - 保证真正向量化前模型已经存在，不会出现 "model not found"
4. **后端通过服务名访问 Ollama**：`RAG_EMBEDDING_BASE_URL=http://ollama:11434`，
   这个值是 compose 文件里写死的（不会误连到 127.0.0.1 → 本机 Ollama）

如果想**换向量模型**（比如 nomic-embed-text / mxbai-embed-large）：

```bash
# 1) 改 .env
RAG_EMBEDDING_MODEL=mxbai-embed-large

# 2) 重新初始化（后端也会重启，应用新模型名）
docker compose run --rm ollama-init
docker compose restart backend
```

### 9.5 数据持久化 & 备份

compose 中所有数据都走命名卷，不会因为 `compose down` 丢失：

| named volume | 存什么 | 备注 |
|---|---|---|
| `ollama_data` | `/root/.ollama`（bge-m3 等模型 blobs）| 最大；首次下载完后别删 |
| `backend_logs` | `/app/log`（Logback 三个日志）| 排查 RAG 注入、SSE 报错用 |
| `mysql_data` | `/var/lib/mysql`（task + 知识库数据）| 知识库跨容器重建持久 |
| `ollama_webui_data` | WebUI 用户/会话 | profile=webui 才创建 |

一键备份（`backup/` 下）：

```bash
./deploy.sh backup
# backup/jarvis-20260829-170500/
#   ├── backend-logs.tar.gz     # 后端日志
#   ├── mysql-jarvis.sql.gz     # MySQL 逻辑备份（mysqldump）
#   └── ollama-models.tar.gz    # Ollama 模型
```

MySQL 恢复（示例）：

```bash
gunzip -c backup/jarvis-xxx/mysql-jarvis.sql.gz | \
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

### 9.6 GPU 加速（可选）

Ollama 容器默认 CPU，CPU 跑 bge-m3 embedding 已经够用（单批 ≈ 100ms 级）。
如果有 Nvidia GPU：

1. 安装 `nvidia-docker`
2. `docker-compose.yml` 里 `ollama` 服务下取消注释 `deploy.resources.reservations.devices` 那段
3. `docker compose up -d --force-recreate ollama`
4. `ollama-init` 跑完后看 WebUI 或 `docker exec jarvis-ollama nvidia-smi`，确认 GPU 被占用

### 9.7 部署到公网要改的几件事

| 项 | 建议 |
|---|---|
| 端口暴露 | 只暴露 `frontend`（8080），`OLLAMA_PORT` / `JARVIS_BACKEND_PORT` 从 compose 的 `ports:` 里删掉 |
| H2 Console | 在 `nginx.conf.template` 里删除 `/h2-console/` 那段；或生产改 H2 为 PostgreSQL |
| HTTPS | 在前端之前加 Caddy / Nginx 做 TLS，certbot 签 Let's Encrypt |
| Key 校验 | 确认 `.env` 里 `AGENTSCOPE_API_KEY` 真实有效，别把 `.env.example` 抄过去 |
| 限流 | Nginx 加 `limit_req_zone` 限 `/api/agent/chat/stream`（SSE 耗 token）|
| 知识库 | 导入的文档会进容器的卷；记得定期 `./deploy.sh backup` |

---

## 10. 快速自检清单（上线前必查）

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

## 10. 关闭服务

### 10.1 本地开发部署

```bash
# 前端：Ctrl+C 停 dev server，或：
lsof -ti:5173 | xargs kill

# 后端：
lsof -ti:8080 | xargs kill

# Ollama（如启动方式是前台 ollama serve）：
pkill -x ollama
# Ollama App 方式在状态栏图标 → Quit 即可
```

### 10.2 Docker 部署

```bash
./deploy.sh down      # 保留数据卷，下次 up 不用重拉 bge-m3 / 重新编译
# 或：docker compose down

# 真正干净地连数据卷一起删（会丢失知识库数据！慎用）
docker compose down -v
```

---

## 11. 配置文件索引

| 配置点 | 位置 |
|---|---|
| 数据源 / H2 / MyBatis / RAG / AgentScope / MCP | `src/main/resources/application.properties` |
| Logback 滚动策略 | `src/main/resources/logback-spring.xml` |
| H2 表结构（含 `knowledge_*` 两张表）| `src/main/resources/schema.sql` |
| Vite 端口 / `/api` 代理目标 | `web/vite.config.js` |
| Axios 封装 / 知识库 API 封装 | `web/src/api/client.js` |
| 全局错误格式（401/429/500 JSON）| `controller/GlobalExceptionHandler.java` |
| Compose 编排 + 服务依赖 + 自动 pull bge-m3 | `docker-compose.yml` |
| 后端镜像：maven 分层缓存 + JRE 运行 | `deploy/Dockerfile.backend` |
| 前端镜像：Vite build + Nginx 反代 | `deploy/Dockerfile.frontend` |
| Nginx SSE 无缓冲 + SPA fallback | `deploy/nginx.conf.template` |
| 环境变量模板（API Key / 端口 / 模型）| `deploy/.env.example` |
| 一键脚本（up/down/check/backup）| `deploy.sh` |
