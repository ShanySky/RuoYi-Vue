# RuoYi AI 项目代理约定

## 1. 仓库角色

本仓库是 RuoYi AI 助手项目的后端仓库：`ShanySky/RuoYi-Vue`。

配套前端仓库是 `ShanySky/RuoYi-Vue3`。本仓库同时承担跨前后端 AI 项目的 Architecture Repository 和项目级 AI 协作规则 canonical source。

项目长期产品目标分三层：

1. AI 理解真实业务并帮助人类用户完成业务。
2. 虚拟员工在授权范围内自主承担业务。
3. 在成熟治理机制下形成受控、可审计的持续自我改进能力。

“把 RuoYi 演进成 AI 时代的软件开发框架”是服务上述目标的技术战略，不是独立目的。每个阶段只做当前真实需要的能力，不提前建设无需求的平台。

## 2. 开始工作前

每次实际工作前先确认 GitHub 当前状态，不依赖历史会话或静态快照：

- 目标仓库和目标分支。
- `chatgpt/ai-agent-assistant` 与 `master` 的当前关系。
- 最新相关代码、配置、SQL、测试和 workflow。
- 本任务是否存在独立讨论 / 实施分支。

分支基本职责：

- `master`：RuoYi 干净基线。
- `chatgpt/ai-agent-assistant`：已经成熟、验收后的稳定 AI 开发主线。
- 架构讨论、专项实现、迁移或高风险改造：默认从最新 AI 主线创建独立工作分支。

涉及 Git 历史、分支、合并、Squash、Rebase 时继续读取：

- `.agents/rules/git-workflow.md`

## 3. 核心原则

- 从真实产品诉求出发，不为技术形式增加复杂度。
- 已确认架构结论不要无故推翻；真实矛盾出现时明确提出并重新讨论。
- AI 后端继续复用 RuoYi Controller / Service / API / 权限 / 数据范围 / 校验 / 操作日志，不建立第二套业务后端。
- 安全边界不能只依赖 Prompt；权限、风险、Run 状态、Tool Policy 和 WRITE 约束必须由代码保证。
- 前后端联动修改必须同时检查 `ShanySky/RuoYi-Vue3` 的协议和当前实现。
- 能自动完成的验证尽量自动完成；重要 Agent 改动优先做真实编译、启动、接口、数据库和端到端验证。
- “代码改完”或“编译成功”都不能单独代表任务完成。
- 不提交真实 AI Token、密码、密钥或其他敏感凭据。
- 规则和文档服务开发，不为了形式完整持续堆积上下文。

## 4. 默认 Reading Order

不要默认读取全部项目资料。根据当前任务按以下顺序恢复上下文：

1. **当前事实**：目标分支、相关代码、配置、测试、workflow。
2. **本文件**：确认仓库角色、核心原则和任务路由。
3. **任务相关 Rule / Skill**：只读取当前任务需要的文件。
4. **相关架构 / 阶段文档**：只读取能约束本任务的文档。
5. **实施事实**：回到真实代码和运行结果完成判断。

完整的资料分层说明见：

- `.agents/README.md`

## 5. 任务路由

### Git / 分支 / 合并 / 历史整理

读取：

- `.agents/rules/git-workflow.md`

### 编写或整理“推进目标”

读取：

- `.agents/rules/progress-goal.md`

推进目标只负责导航：目标、核心诉求、工作依据、阶段关系、关键边界和完成标准；不要把已有方案重新抄一遍。

### 全栈运行、E2E、AI Agent 闭环验收

读取：

- `.agents/skills/fullstack-validation/SKILL.md`

根据改动范围决定验证深度，不机械启动整套环境；但涉及数据库、Redis、Spring 配置、前后端接口、页面交互、Agent / Tool Loop 时优先真实运行。

### AI 总体架构 / 长期方向

优先读取：

- `doc/ai/00-全局设计/00-AI助手总体设计.md`
- `doc/ai/00-全局设计/02-AI助手关键设计决策.md`

需要快速了解当前能力与下一步时再读取：

- `doc/ai/00-全局设计/01-AI助手能力路线图与实现状态.md`

### Phase 3.5 / B4～F 架构讨论

读取当前架构讨论分支中的：

- `doc/ai/00-全局设计/00-AI助手总体设计.md`
- `doc/ai/04-架构复盘与演进/14-AI助手三阶段架构复盘与后续优化建议.md`

必要时再读取已经独立实施完成的 15～17 号文档，避免重新讨论或重复实现已完成事项。

### 普通后端修改

先定位真实代码和测试；只有当修改触及长期架构边界时才加载总体设计或阶段文档，不为简单任务扩大上下文。

### 跨前后端修改

同时读取前端仓库当前目标分支的 `AGENTS.md` 和相关真实代码。项目级架构与规则以本仓库 canonical source 为准，前端专属实现事实以前端仓库为准。

## 6. 当前后端主干

AI 后端能力集中在 `ruoyi-ai`，核心边界包括：

- Provider / Model。
- Conversation / Message。
- Run Lifecycle / Stop / Steering。
- AgentRuntime 及当前 Spring AI Adapter。
- Tool Call / Page Capability Policy。
- Prompt / Prompt Version。
- Context / Checkpoint / Compaction。
- AI 配置、偏好、审计和运行诊断。

仓库真实代码始终优先于文档中的历史快照；如果实现与已确认设计冲突，应明确指出，而不是静默选择其中一份。

## 7. 新增 Rule / Skill 的门槛

只有稳定、重复的真实开发场景才新增 Rule 或 Skill：

- Rule：违反会造成风险、返工或架构漂移的稳定约束。
- Skill：已经重复验证、步骤相对稳定的可执行工作流。

不要为了目录完整预建规则体系或空 Skill。
