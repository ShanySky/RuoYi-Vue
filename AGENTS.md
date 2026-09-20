# RuoYi AI 项目代理约定

## 1. 仓库角色

本仓库是 RuoYi AI 助手项目的后端仓库：`ShanySky/RuoYi-Vue`。

配套前端仓库是 `ShanySky/RuoYi-Vue3`。本仓库同时承担跨前后端 AI 项目的 Architecture Repository，长期架构、关键设计决策和阶段文档统一维护在 `doc/ai/`。

项目长期产品目标分三层：

1. AI 理解真实业务并帮助人类用户完成业务。
2. 虚拟员工在授权范围内自主承担业务。
3. 在成熟治理机制下形成受控、可审计的持续自我改进能力。

“把 RuoYi 演进成 AI 时代的软件开发框架”是服务上述目标的技术战略，不是独立目的。每个阶段只做当前真实需要的能力，不提前建设无需求的平台。

## 2. 通用开发原则

- 从真实产品诉求出发，不为技术形式增加复杂度。
- 项目交流、方案、计划、验收记录和面向用户的说明默认使用中文；代码标识、路径、类名、接口名、命令和专有名称可保持英文。
- 已确认架构结论不要无故推翻；真实矛盾出现时明确提出并重新讨论。
- AI 后端继续复用 RuoYi Controller / Service / API / 权限 / 数据范围 / 校验 / 操作日志，不建立第二套业务后端。
- 安全边界不能只依赖 Prompt；权限、风险、Run 状态、Tool Policy 和 WRITE 约束必须由代码保证。
- 前后端联动修改应同时检查配套前端的协议和当前实现。
- 能自动完成的验证尽量自动完成；重要 Agent 改动优先做真实编译、启动、接口、数据库和端到端验证。
- “代码改完”或“编译成功”都不能单独代表任务完成。
- 不提交真实 AI Token、密码、密钥或其他敏感凭据。
- 仓库规则和文档服务开发，不为了形式完整持续堆积上下文。

## 3. 分支与资料

分支基本职责：

- `master`：RuoYi 干净基线。
- `chatgpt/ai-agent-assistant`：已经成熟、验收后的稳定 AI 开发主线。
- 较大的架构讨论、专项实现、迁移或高风险改造：从最新 AI 主线建立独立工作分支。

通用仓库资料：

- `.agents/rules/git-workflow.md`：Git 分支、历史整理和合并安全约束。
- `.agents/rules/progress-goal.md`：推进目标的项目级写法。
- `.agents/skills/fullstack-validation/SKILL.md`：真实运行与全栈验收工作流。
- `doc/ai/00-全局设计/00-AI助手总体设计.md`：长期总体设计。
- `doc/ai/00-全局设计/02-AI助手关键设计决策.md`：关键设计决策。
- `doc/ai/00-全局设计/01-AI助手能力路线图与实现状态.md`：能力与实现状态索引。

仓库当前代码、配置、SQL、测试、workflow 和真实运行结果始终是实现状态的最终事实来源。

## 4. 当前后端主干

AI 后端能力集中在 `ruoyi-ai`，核心边界包括：

- Provider / Model。
- Conversation / Message。
- Run Lifecycle / Stop / Steering。
- AgentRuntime 及当前 Spring AI Adapter。
- Tool Call / Page Capability Policy。
- Prompt / Prompt Version。
- Context / Checkpoint / Compaction。
- AI 配置、偏好、审计和运行诊断。

如果实现与已确认设计冲突，应明确指出并处理，而不是静默选择其中一份。

## 5. Rule / Skill 的门槛

只有稳定、重复的真实开发场景才新增 Rule 或 Skill：

- Rule：违反会造成风险、返工或架构漂移的稳定约束。
- Skill：已经重复验证、步骤相对稳定的可执行工作流。

不要为了目录完整预建规则体系或空 Skill。
