# RuoYi AI 项目代理约定

## 1. 项目定位

本仓库是 RuoYi AI 助手项目的后端仓库：`ShanySky/RuoYi-Vue`。
配套前端仓库是 `ShanySky/RuoYi-Vue3`，前后端共同构成一个完整的 AI 业务系统。

项目长期产品目标分三层：

1. AI 理解真实业务并帮助人类用户完成业务。
2. 虚拟员工在授权范围内自主承担业务。
3. 在成熟治理机制下形成受控、可审计的持续自我改进能力。

“把 RuoYi 演进成 AI 时代的软件开发框架”是服务上述产品目标的技术战略，不是脱离业务目标独立存在的目的。每个阶段只做当前阶段真正需要的能力，为下一阶段留清晰边界，但不要过度提前建设。

## 2. 当前开发基线

- `master`：RuoYi 基线，尽量保持干净。
- `chatgpt/ai-agent-assistant`：AI 稳定开发主线。
- 架构讨论、专项实现、迁移或高风险改造：默认从最新 AI 主线另开独立分支。
- 较大的实现任务不要直接在 AI 主线上开发。

每次开始实际工作前，应重新检查 GitHub 当前代码、分支和文档状态，不要只依赖历史会话或静态说明。

## 3. 当前后端主干

AI 后端能力集中在 `ruoyi-ai`，当前核心边界包括：

- Provider / Model。
- Conversation / Message。
- Run Lifecycle / Stop / Steering。
- AgentRuntime 及当前 Spring AI Adapter。
- Tool Call / Page Capability Policy。
- Prompt / Prompt Version。
- Context / Checkpoint / Compaction。
- AI 配置、偏好、审计和运行诊断。

继续复用 RuoYi 原有 Controller / Service / API / 权限 / 数据范围 / 校验和操作日志。除非出现明确的新需求和充分理由，不另建第二套 AI 业务框架。

## 4. 核心开发原则

- 从真实产品诉求出发，不为技术形式本身增加复杂度。
- 已确认的架构结论不要无故推翻；发现真实矛盾时应明确提出并重新讨论。
- 安全边界不能只依赖 Prompt，权限、风险、Run 状态和写操作约束必须由代码保证。
- 能自动完成的验证尽量自动完成；重要 Agent 改动优先真实编译、启动、接口、数据库和端到端验证。
- 不把“编译成功”或“代码改完”单独视为任务完成。
- 不提交真实 AI Token、密码、密钥或其他敏感凭据。
- 前后端联动改动要同时考虑 `ShanySky/RuoYi-Vue3` 的契约和当前实现。
- 规则和文档服务开发，不为了形式完整持续堆积上下文。

## 5. 重要设计资料

涉及 AI 架构或 Phase 3.5 时，优先阅读当前分支中的：

- `doc/ai/00-全局设计/00-AI助手总体设计.md`
- `doc/ai/04-架构复盘与演进/14-AI助手三阶段架构复盘与后续优化建议.md`

仓库真实代码始终优先于文档中的历史快照。

## 6. 场景规则

涉及分支、提交、合并、Squash、Rebase 或 Git 历史整理时，必须继续阅读：

- `.agents/rules/git-workflow.md`

后续只有出现稳定、重复的真实开发场景时，再增加新的规则文件；不要预先建设庞大的规则体系。
