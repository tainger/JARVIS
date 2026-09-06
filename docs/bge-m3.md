# bge-m3 向量化模型说明

> 本文档说明 bge-m3 在 JARVIS 项目中的作用、选型理由与运维要点。
> 关联文档：[rag-design.md](rag-design.md)（RAG 整体架构与原理）、[setup-guide.md](setup-guide.md)（启动与配置）。

---

## 一、核心作用

bge-m3 是一个**多语言文本向量化（Embedding）模型**。它把任意文本（文档片段、用户问题）转换成一串固定长度的数字向量（1024 维），让语义相近的文本在向量空间里距离也近。

这样就能用数学方法（余弦相似度）快速找出"意思最像"的内容，而不是靠关键词匹配——即使没出现相同的字，只要语义相近也能命中。

```
文本 "差旅报销需在10个工作日内提交"
        │
        ▼  bge-m3
[0.064, -0.011, 0.008, ..., -0.003]  ← 1024 维浮点向量
        │
        ▼  存入 knowledge_chunk.embedding
向量数据库 / 内存索引
```

---

## 二、在 JARVIS 中的具体工作

bge-m3 在 RAG 链路的**两个关键节点**工作：

### 节点 1：文档导入时（写时）

```
文档导入  →  清洗 HTML  →  分块（500 字符）  →  bge-m3 向量化每一块  →  存入 DB
                                                          ↑
                                                   bge-m3 在这里
```

### 节点 2：用户提问时（读时）

```
用户提问  →  bge-m3 向量化问题  →  与所有块算余弦相似度  →  Top-K 命中  →  注入 prompt 给 DeepSeek
                  ↑
           bge-m3 也在这里
```

### 实测示例

| 步骤 | 输入 | bge-m3 输出 |
|---|---|---|
| 导入 | "差旅报销需要在出差结束后 10 个工作日内提交" | 1024 维向量 → 存入 `knowledge_chunk` |
| 提问 | "报销有什么时间限制" | 1024 维向量 → 与所有块算相似度 |
| 命中 | — | Top1 score=0.7143，精准命中团队手册报销制度块 |

> 正因为 bge-m3 把"报销时间限制"和"差旅报销需在10个工作日内提交"映射到了相近的向量区域，即使措辞不同也能精准命中。

---

## 三、为什么选 bge-m3

| 特性 | 说明 |
|---|---|
| **多语言强** | 中英文混合场景表现优秀，JARVIS 知识库主要是中文 |
| **1024 维** | 兼顾表达能力与检索速度，适合内存索引全量遍历 |
| **支持密集+稀疏+多向量** | 模型本身支持混合检索；JARVIS 在此基础上额外加了 0.25 词面分兜底精确词 |
| **Ollama 可直接跑** | 本地 CPU 推理，无需 GPU，无需 API Key，隐私可控 |
| **keep_alive 常驻** | 首次加载约 8s，之后请求毫秒级（项目配置 60m 不卸载） |
| **体积适中** | 约 1.2GB，Ollama 拉取快，冷加载可接受 |

---

## 四、在整条 RAG 链路的位置

bge-m3 **只负责"文本 → 向量"这一步**，不负责生成回答。完整链路如下：

```
┌─────────────┐   ┌──────────┐   ┌──────────────┐   ┌─────────────┐   ┌──────────┐   ┌──────────┐
│  文档/问题   │ → │  bge-m3  │ → │  向量索引    │ → │  相似度检索  │ → │  DeepSeek │ → │  最终答案 │
└─────────────┘   └──────────┘   └──────────────┘   └─────────────┘   └──────────┘   └──────────┘
                      ↑                                    ↑
                   向量化                              Top-K 命中
                  bge-m3 在这里                       （不需要 bge-m3）
```

**一句话定位**：bge-m3 是 RAG 的"语义翻译官"，把人类语言翻译成机器能算相似度的数学语言；真正生成答案的是 DeepSeek。

---

## 五、配置与运维

### 相关配置项（application.properties）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.embedding.base-url` | `http://127.0.0.1:11434` | Ollama 服务地址 |
| `rag.embedding.model` | `bge-m3` | 模型名 |
| `rag.embedding.timeout-seconds` | `120` | 超时需覆盖冷加载耗时 |
| `rag.embedding.keep-alive` | `60m` | 模型常驻内存，避免冷加载 |
| `rag.embedding.batch-size` | `4` | 单次 HTTP 请求条数；CPU 推理约 6s/条，大批次会撞超时墙 |

### Ollama 运维命令

```bash
# 启动服务
# 注意：/tmp/ollama-models 是本项目 sandbox 环境的特殊配置（默认 ~/.ollama 不可写），
# 普通环境直接执行 ollama serve 即可，无需指定 OLLAMA_MODELS
OLLAMA_MODELS=/tmp/ollama-models ollama serve

# 拉取模型（约 1.2GB）
ollama pull bge-m3

# 查看已安装模型
ollama list

# 直接测试 embedding 接口
curl -X POST http://127.0.0.1:11434/api/embed \
  -H "Content-Type: application/json" \
  -d '{"model":"bge-m3","input":"测试文本","keep_alive":"60m"}'
```

### 实测性能数据

| 指标 | 数值 | 备注 |
|---|---|---|
| 向量维度 | 1024 | `float[]` |
| 首次模型加载 | ~8.5s | CPU 推理，之后 keep_alive 常驻 |
| 单次 embedding | ~0.2s | 模型已加载后 |
| RAG 检索响应 | 0.46s | 含 query 向量化 + 36 块全量评分 + 排序 |
| 端到端对话注入 | 1.7s | 含检索 + DeepSeek 生成 + 引用标注 |

### 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `error starting runner: fork/exec ... no such file` | 使用了 `/Applications/Ollama.app` GUI 版，runner 路径损坏 | 改用 CLI 版：`OLLAMA_MODELS=/tmp/ollama-models ollama serve` |
| embedding 请求超时 | 模型冷加载过慢或 batch 太大 | 增大 `rag.embedding.timeout-seconds` 或调小 `batch-size` |
| 模型丢失（重启后 `ollama list` 为空） | `/tmp/ollama-models` 是临时目录，重启后清空 | 重新 `ollama pull bge-m3` |

---

## 六、相关文档

- [rag-design.md](rag-design.md) — RAG 整体架构、分块策略、混合评分、双入口设计
- [setup-guide.md](setup-guide.md) — 完整启动与配置指南（含 Ollama & bge-m3 详解）
