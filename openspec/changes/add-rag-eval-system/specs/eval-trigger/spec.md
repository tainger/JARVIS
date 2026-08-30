## Purpose

让评测在正确的时机自动发生：一键命令覆盖日常手动场景，pre-push 钩子拦截将进仓库的回归，GitHub Actions 在远端持续守护——三档触发强度递进，均路径感知以避免无谓的评测开销。

## ADDED Requirements

### Requirement: 一键评测命令

项目 SHALL 提供统一的评测入口命令（Maven profile 或等价封装），执行检索层评测并生成归档，无需使用者记忆环境变量与测试类名组合。生成层评测 SHALL 通过独立参数显式开启（因其消耗 token 且要求后端运行中）。

#### Scenario: 开发者手动跑评测

- **WHEN** 开发者在仓库根目录执行统一评测入口命令
- **THEN** 检索层评测运行并产出归档
- **AND** 未开启生成层参数时不调用任何 LLM API

### Requirement: pre-push 钩子路径感知拦截

项目 SHALL 提供钩子安装脚本，安装后：git push 前，若本次待推送提交变更了以下任一路径——`src/main/java/com/example/jarvis/rag/**`、`src/main/resources/application.properties`、`schema.sql`、`src/test/resources/rag-eval-cases.json`、`src/test/java/**/rag/**`——SHALL 先运行检索层评测，任一指标断言失败 MUST 阻止 push；若未变更上述路径 SHALL 跳过评测直接放行。钩子 MUST 提供 `--no-verify` 逃生通道说明。

#### Scenario: 修改检索评分后 push

- **WHEN** 开发者修改了 `KnowledgeService` 的混合评分逻辑并 `git push`（已装钩子）
- **THEN** push 前自动运行检索层评测
- **AND** 评测失败时 push 被拒绝并显示失败指标

#### Scenario: 仅修改前端代码后 push

- **WHEN** 开发者只修改了 `web/src/pages/Chat.jsx` 并 `git push`
- **THEN** 钩子跳过评测，直接 push

### Requirement: CI 工作流

仓库 SHALL 包含 GitHub Actions 工作流：当 push 或 PR 触及上述评测相关路径时运行检索层评测（CI 环境内含 Ollama 服务并预置 bge-m3 模型），评测失败 MUST 将 check 置为失败；归档报告 MUST 作为 workflow artifact 上传。工作流 MUST 不默认运行生成层评测（无 API Key）。

#### Scenario: PR 修改分块参数

- **WHEN** 向仓库提交修改 `rag.chunk.max-chars` 默认值的 PR
- **THEN** CI 触发检索层评测并将报告上传为 artifact
- **AND** 指标不达基线时 PR check 显示失败
