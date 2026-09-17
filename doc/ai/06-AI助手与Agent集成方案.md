# RuoYi AI 助手与 Agent 集成方案

> 状态：第一阶段已实现并通过全栈自动验收（2026-09-18）  
> 适用仓库：`ShanySky/RuoYi-Vue` + `ShanySky/RuoYi-Vue3`  
> 目标：先完成设计，再按设计落地；第一阶段必须形成可配置、可对话、可调用页面工具、可真实修改业务数据的闭环。

## 1. 产品目标

在现有 RuoYi 管理系统中加入内置 AI 助手。管理员能够配置一个 OpenAI-compatible 大模型服务地址和令牌，系统自动同步模型列表并选择启用/默认模型；普通用户在业务页面可打开 AI 助手，通过自然语言聊天、查询和操作当前页面。

第一阶段不是浏览器自动化。AI 不通过 Playwright、坐标、DOM selector 模拟用户，而是由 Vue 页面主动注册结构化的 Frontend Tools（Page Actions），Agent 按工具协议调用。

## 2. 第一阶段闭环

1. 管理员打开“AI 配置”。
2. 填写 Provider Base URL 与 Token。
3. 测试连接。
4. 从 `{baseUrl}/models` 同步模型。
5. 勾选允许使用的模型并设置默认模型。
6. 用户进入“系统管理 → 用户管理”。
7. 打开全局 AI 助手，选择已启用模型。
8. 普通聊天可得到回答。
9. 用户要求查询用户时，模型调用当前页面注册的 `page_system_user_search`。
10. 页面真实填写查询条件并刷新列表。
11. 用户要求打开记录时，调用 `page_system_user_edit_open`，真实打开编辑弹窗。
12. 用户要求修改但暂不保存时，调用 `page_system_user_edit_set_fields`，只改变 Vue 表单状态。
13. 用户确认保存后，调用 `page_system_user_edit_submit`；WRITE 级工具需显式确认。
14. 页面继续复用原有 `updateUser()` HTTP API；后端原有 `@PreAuthorize`、数据权限、业务校验、操作日志继续生效。
15. Agent 获得工具执行结果并生成最终答复。
16. 刷新页面重新查询，数据库结果仍正确。

## 3. 总体架构

```text
RuoYi Vue3
├─ AiAssistant（全局聊天抽屉）
├─ AiToolRegistry（当前页面工具注册表）
├─ Page Context（route / 当前状态摘要）
└─ User page Frontend Tools
       │
       │ HTTP Agent Turn Protocol
       ▼
RuoYi Spring Boot
└─ ruoyi-ai
   ├─ ProviderConfigService
   ├─ ModelCatalogService
   ├─ AgentLoopService
   ├─ ConversationService
   ├─ ToolPolicyService
   ├─ CryptoService
   └─ Spring AI OpenAI ChatModel
       │
       ▼
OpenAI-compatible Provider
```

扩展边界：

```text
AgentRuntime
├─ BuiltInSpringAiRuntime   ← 第一阶段实现
├─ CodexHarnessAdapter      ← 预留
└─ LocalHarnessAdapter      ← 预留
```

第一阶段不要求额外部署 Codex/Local Harness；管理员只配置 URL + Token 即可工作。

## 4. 技术选型

### 4.1 后端

- Java 17（沿用当前 master）。
- Spring Boot 4.1.0（沿用当前 master）。
- Spring AI 2.0.1。
- `OpenAiChatModel` + user-controlled tool loop。
- MyBatis（沿用 RuoYi）。
- MySQL 持久化 Provider、模型、会话及消息。
- 原有 Spring Security / RuoYi Permission / Data Scope 作为最终业务权限边界。

选择 Spring AI 2.0.1 的原因：

- 2.0.x 与 Spring Boot 4.0/4.1 对齐。
- OpenAI Chat 支持运行时 `baseUrl`、`apiKey`、`model`。
- 底层 `ChatModel` 不自动执行工具，能读取原始 Tool Call，适合浏览器外部工具、审批和暂停/恢复 Agent Loop。
- 避免自己维护各家 OpenAI-compatible tool-call JSON 解析。

### 4.2 前端

- 继续使用 Vue 3.5 + Element Plus + Pinia + Axios。
- 不引入完整 CopilotKit Runtime。
- 自研轻量 `AiToolRegistry` / `useAiPageTools`，借鉴 CopilotKit Vue v2 `useFrontendTool` 的生命周期模型。
- 不使用 DOM selector 作为 AI 工具契约。

理由：现有 RuoYi-Vue3 是轻量纯 JS 工程，引入 CopilotKit Runtime/AG-UI 会扩大依赖和后端协议面；第一阶段仅需要 Frontend Tool 核心模式，自建薄层更可控。

### 4.3 Agent 通信协议

第一阶段使用简单的 HTTP Turn 协议，而非 WebSocket：

- `POST /ai/chat/turn`
- 用户消息或 Tool Result 都通过同一入口推进 Agent Loop。
- 后端返回三类主要状态：`MESSAGE`、`TOOL_CALL`、`ERROR`。
- 遇到前端 Tool Call，后端暂停；浏览器执行并把结果作为 Tool Result 再次 POST，后端继续。

第一阶段不把 token-by-token 流式输出设为闭环的必要条件。架构保留后续 SSE 流式事件升级点，但优先确保工具调用、权限、审批和恢复语义正确。

## 5. Agent Loop

### 5.1 输入

每次 turn 包含：

- conversationId（新会话可为空）
- modelId / modelCode
- userMessage 或 toolResult（二选一）
- 当前 route
- pageContext（有限结构化摘要）
- frontendTools（当前页面实际注册且当前用户有权使用的工具定义）

### 5.2 模型工具

模型看到：

- 当前页面 Frontend Tools。
- 后续可扩展的 Server Tools。

OpenAI 工具名统一使用 `[a-z0-9_]`，例如：

- `page_system_user_search`
- `page_system_user_edit_open`
- `page_system_user_edit_set_fields`
- `page_system_user_edit_submit`

### 5.3 暂停与恢复

模型请求 Frontend Tool 时：

1. 后端保存 assistant tool-call message。
2. 创建 pending call（callId、toolName、arguments、riskLevel、userId、conversationId）。
3. 返回 `TOOL_CALL`。
4. 浏览器检查风险级别；必要时向用户确认。
5. 浏览器执行注册 handler。
6. 回传 Tool Result。
7. 后端校验 callId / conversation / user / tool 后写入 tool response message。
8. 使用完整历史再次调用模型。
9. 重复直到最终 `MESSAGE`。

第一阶段关闭 provider 的 parallel tool calls，避免同时存在多个需要浏览器交互的 pending call，降低状态机复杂度。

### 5.4 限制

- 单个用户 turn 最多 8 次工具迭代。
- Tool Result 大小限制。
- frontendTools 数量和 schema 大小限制。
- pending call 有超时状态。

## 6. Frontend Tool 模型

每个页面工具包含：

```text
name
page
summary
description
inputSchema
riskLevel
requiredPermission
handler
getContext? (可选)
```

风险级别：

- `READ`：读取/筛选/查询；无需确认。
- `UI`：打开弹窗、填写尚未提交的表单；无需确认。
- `WRITE`：真正产生业务写入；默认要求确认。
- `DANGEROUS_WRITE`：删除、批量破坏性操作、重置敏感凭据；强制确认。

第一阶段用户管理页只注册四个工具：

1. `page_system_user_search` — READ
2. `page_system_user_edit_open` — UI
3. `page_system_user_edit_set_fields` — UI
4. `page_system_user_edit_submit` — WRITE

不注册删除、重置密码等高风险动作。

## 7. Page Context

页面只向模型提供完成任务所需的结构化状态，不上传完整 DOM。

用户管理页第一阶段包括：

- route / pageName
- 当前查询条件
- total
- 当前页记录的最小标识信息（userId、userName、nickName）
- 当前选中 userId
- 编辑弹窗是否打开、正在编辑 userId
- 当前可用工具列表

敏感字段（密码、Token）不得进入 Page Context。

## 8. 权限模型

核心原则：AI 权限不超过当前登录用户。

1. Frontend Tool 只有在 `requiredPermission` 通过时才注册/上报。
2. 即使前端误注册，真正业务 HTTP API 仍经过 RuoYi 后端权限与数据范围检查。
3. Provider 配置接口仅管理员/授权用户可访问。
4. 会话、pending tool call 必须绑定当前 RuoYi userId。
5. 后端不使用超级管理员 Token 代替用户执行业务动作。

第一阶段页面 Tool 最终仍调用现有 `/system/user/*` API，所以现有 `@PreAuthorize`、数据范围、业务校验和操作日志继续作为最终防线。

## 9. Provider / Model 管理

第一阶段 Provider 类型固定为 `OPENAI_COMPATIBLE`，数据结构保留 provider_id，便于未来多 Provider。

配置项：

- name
- baseUrl
- token（加密保存）
- enabled
- timeoutSeconds

模型：

- modelCode
- displayName
- enabled
- defaultModel
- toolCapability (`UNKNOWN` / `SUPPORTED` / `UNSUPPORTED`)
- lastSyncTime

能力：

- 测试连接。
- `GET {baseUrl}/models` 同步模型。
- 选择启用模型。
- 设置唯一默认模型。
- 普通聊天测试。
- Tool Calling 无副作用能力测试（模型请求 `ai_echo_test` 即视为支持）。

## 10. Token 安全

- Token 不明文存库。
- AES/GCM 加密。
- 首选 `AI_MASTER_KEY` 环境变量；为空时第一阶段允许从现有 `token.secret` 派生密钥以保证开箱可运行，正式环境建议显式配置独立 Master Key。
- GET 配置接口只返回 `hasToken` / mask，不返回密文或明文。
- 更新配置时 Token 为空代表保留旧 Token。
- 日志不得打印请求 Authorization 或 Token。

## 11. 数据库

新增：

- `ai_provider`
- `ai_model`
- `ai_conversation`
- `ai_message`
- `ai_pending_tool_call`

不修改 RuoYi 现有业务表结构。

## 12. UI

### 12.1 AI 配置页

提供：

- Base URL
- Token
- 测试连接
- 同步模型
- 模型列表（启用开关 / Tool capability）
- 默认模型
- Tool Calling 测试
- 保存

### 12.2 全局 AI Assistant

挂在 `src/layout/index.vue`，只在登录后的 Layout 中存在。

包含：

- 浮动入口
- 右侧 Drawer
- 模型选择
- 新建会话
- 用户/AI 消息
- Tool Call 状态卡片
- WRITE 确认卡片
- 错误和重试提示

## 13. 模块划分

后端新增 `ruoyi-ai` Maven module，`ruoyi-admin` 依赖它。

建议包：

```text
com.ruoyi.ai
├─ controller
├─ domain
├─ dto
├─ mapper
├─ service
├─ runtime
├─ tool
├─ security
└─ util
```

前端新增：

```text
src/
├─ api/ai/
├─ ai/
│  ├─ toolRegistry.js
│  └─ pageContext.js
├─ components/AiAssistant/
└─ views/ai/config/
```

## 14. 失败处理

- Provider 连接失败：配置页显示可诊断错误，不影响普通 RuoYi 功能。
- 模型不存在/被禁用：拒绝启动 turn。
- Tool Calling 不支持：模型仍允许普通聊天，但当前 Agent 操作提示“不支持工具调用”。
- 页面切换导致工具消失：pending call 返回 `TOOL_UNAVAILABLE` 给模型继续解释。
- Tool handler 抛错：错误作为 tool result 返回模型，同时 UI 显示失败。
- 后端业务 API 权限失败：不绕过，原样转为工具失败。
- Agent Loop 超过次数：终止并返回明确错误。

## 15. 验收策略

必须做真实运行验证：

- MySQL / Redis 启动。
- AI SQL 初始化。
- Maven 完整构建。
- `ruoyi-admin.jar` 真正启动。
- `/captchaImage` 请求成功。
- AI Provider 配置接口真实请求。
- 使用 CI Mock OpenAI-compatible Provider 验证 `/models`、聊天、Tool Call。
- npm 安装/构建。
- Vite 启动与 `/dev-api` 代理。
- 浏览器端关键交互自动验收（测试可使用 Playwright；产品运行机制本身不使用 Playwright）。
- 用户管理页查询→打开编辑→改字段→确认提交→数据库/重新查询确认。
- 必要时 Cloudflare Quick Tunnel 提供人工预览。

## 16. 不在第一阶段做的事情

- 本地文件/Office/桌面软件控制。
- Local Harness 正式实现。
- Codex Harness 正式实现。
- 页面任意 DOM/鼠标自动化。
- 删除/重置密码等危险页面 Tool。
- 多 Agent 编排。
- RAG / 向量库。
- 大量业务页面全面改造。

这些都通过 AgentRuntime 与 Tool Registry 接口保留后续扩展空间。

## 17. 设计依据

- 当前 RuoYi 后端 master：Java 17、Spring Boot 4.1.0。
- 当前 Vue3 前端 master：Vue 3.5.x、Vite 6.4.x。
- Spring AI 2.0.x 已支持 Spring Boot 4.0/4.1，并提供 user-controlled Tool Execution。
- CopilotKit Vue v2 的 `useFrontendTool` 已验证“浏览器注册结构化工具、Agent 调用、handler 在前端执行”模式可行。
- 本项目继续坚持“真实启动 > 只编译、接口调用 > 只看日志、页面运行 > 静态检查”。
