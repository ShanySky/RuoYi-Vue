# AI 协作资料分层与读取约定

> 文档类型：AI 开发协作 / 知识分层职责地图
>
> 状态：第一版职责地图，用于后续优化 `AGENTS.md`、`.agents/rules/`、`.agents/skills/` 与 ChatGPT Project 资料；本文件本身不改变现有开发规则。
>
> 适用范围：`ShanySky/RuoYi-Vue` + `ShanySky/RuoYi-Vue3`

## 1. 目标

本项目采用：

- ChatGPT 负责长期架构讨论、任务编排、审核与跨阶段判断。
- GitHub 保存可持续维护的项目事实、架构、规则、工作流与实现状态。
- Agent / Codex 按已经确认的目标、方案和计划实施。
- 真实代码、测试和运行结果作为实现事实的最终依据。

核心原则：

> Conversation 是当前工作台，GitHub 是长期事实源。

不要依赖某个长期会话永久保存项目状态，也不要把所有项目知识长期堆入 ChatGPT Project Instructions 或 `AGENTS.md`。

## 2. 六层职责

### L0：ChatGPT Project —— Bootstrap

只承载非常稳定、用于新会话快速进入项目的信息：

- 项目是什么。
- 两个 GitHub 仓库是什么。
- 默认 AI 开发主线是什么。
- ChatGPT 在项目中的基本角色。
- 工作前必须重新读取 GitHub 当前状态。
- 基本运行与验证能力入口。

不在这里维护：

- 当前做到 B4 / B5 等阶段状态。
- 最近一次提交或 PR。
- 临时 blocker。
- 动态架构结论。
- 会频繁演进的 Agent Rule / Skill 正文。

Project 资料是启动基线，不是仓库现实状态的替代品。

### L1：根 `AGENTS.md` —— Repo Entry Point / Router

每个仓库保留一个短而稳定的 `AGENTS.md`。

它负责：

- 说明本仓库在整个 RuoYi AI 项目中的角色。
- 指明主线与分支基本规则。
- 保存少量不可轻易违反的核心原则。
- 给出默认 Reading Order。
- 根据任务类型路由到对应 Rule / Skill / 设计文档。
- 指明跨仓库 canonical source 在哪里。

它不负责：

- 复制完整架构设计。
- 复制完整测试流程。
- 记录当前阶段进度。
- 堆积所有可能场景的规则。
- 充当第二份总体设计。

原则：

> `AGENTS.md` 是路由器，不是超级系统提示词。

### L2：`.agents/rules/` —— 稳定约束

Rule 用于回答：

> 在某种明确场景下，AI 必须遵守什么约束？

适合进入 Rule 的条件：

1. 已在真实工作中重复出现。
2. 不遵守会造成明显返工、风险或架构漂移。
3. 内容相对稳定。
4. 单靠相关设计文档不容易保证执行一致性。

当前已有：

- `git-workflow.md`

后续候选只有在真实重复需求出现后再增加，例如：

- 推进目标编写规则。
- 跨仓库改动约束。
- 验收边界。

不要预建庞大 Rules 目录。

### L3：`.agents/skills/` —— 可重复执行工作流

Skill 用于回答：

> 遇到一种重复任务，具体怎样把它完成？

Skill 与 Rule 的区别：

- Rule = 必须遵守的约束。
- Skill = 可执行的操作流程。

只有流程已经重复验证、步骤相对稳定时才抽 Skill。

当前两个 AI 主线均尚未建立 `.agents/skills/`。

优先候选：

- `fullstack-validation`：MySQL → Redis → Maven → Spring Boot → API → Vite → 代理 → Agent / Tool Loop → 必要时公网预览。
- 后续若真实重复，再评估架构审核、实施计划执行等 Skill。

不要为了目录完整而提前创建空 Skill。

### L4：`doc/ai/` —— 架构、决策与项目状态

后端仓库 `ShanySky/RuoYi-Vue` 当前承担跨前后端 AI 项目的 Architecture Repository 职责。

项目级 canonical 文档继续集中在这里，包括：

- 长期总体设计。
- 能力路线图与实现状态。
- 关键架构决策。
- 阶段方案。
- 实施计划。
- 实施与验收记录。
- 架构复盘与当前讨论状态。

前端仓库只维护确有必要的前端专属文档；不要为了目录对称复制后端已有的项目级设计。

原则：

> 项目级设计只维护一份 canonical source。

### L5：当前工作 —— Working State

动态工作状态应存在 GitHub，而不是长期依赖 Conversation。

根据任务类型使用：

- 当前架构讨论文档。
- 当前实施方案 / 实施计划。
- GitHub Issue / PR。
- 独立工作分支及其提交。

例如 B4～F 的讨论状态属于当前架构讨论文档，不属于 `AGENTS.md`。

### L6：代码、测试与真实运行 —— Implementation Truth

当文档与实现状态冲突时：

1. 先确认目标分支和最新代码。
2. 以真实代码、配置、CI、数据库结构和运行结果判断当前实现。
3. 必要时回写文档。

代码事实优先，不意味着忽略已经确认的架构决策；如果实现与已确认设计冲突，应明确指出并处理，而不是静默改写设计历史。

## 3. 两个仓库的职责

### 后端 `ShanySky/RuoYi-Vue`

同时承担：

1. RuoYi AI 后端代码仓。
2. 跨前后端 AI 项目的 Architecture Repository。
3. 项目级 AI 协作规则的 canonical source。

建议长期结构：

```text
RuoYi-Vue/
├─ AGENTS.md
├─ .agents/
│  ├─ README.md
│  ├─ rules/
│  └─ skills/
├─ doc/ai/
│  ├─ 00-全局设计/
│  ├─ 01-第一阶段/
│  ├─ 02-第二阶段/
│  ├─ 03-第三阶段/
│  └─ 04-架构复盘与演进/
└─ ruoyi-ai/
```

### 前端 `ShanySky/RuoYi-Vue3`

承担：

1. RuoYi AI 前端代码仓。
2. 前端自身的 Repo Entry Point。
3. 前端专属 Rule / Skill。
4. 必要的项目级规则镜像或明确引用。

不复制：

- 总体设计。
- 能力路线图。
- 项目级架构决策。
- 阶段讨论文档。

跨仓库架构任务应回到后端 canonical 文档。

## 4. 项目级规则与仓库级规则

规则分两类。

### 项目级

对前后端共同生效，例如：

- Git 分支与历史整理。
- 推进目标。
- 跨仓库协作。
- 全栈验收原则。

项目级 canonical source 优先放在后端仓库。

如果某条关键规则必须保证“只打开前端仓库时也能工作”，允许在前端保留精简镜像，但必须明确 canonical source，避免两个版本独立演进。

### 仓库级

只与单一仓库有关，例如：

后端：

- Java / Spring / SQL / Agent Runtime 特定约束。

前端：

- Vue / Page Capability / Tool Registry / UI 验证特定约束。

仓库级规则直接保存在对应仓库。

## 5. 默认恢复上下文流程

新会话或长时间中断后恢复任务时，默认顺序：

```text
用户当前目标
    ↓
确认目标仓库、目标分支和当前 GitHub 状态
    ↓
读取目标仓库 AGENTS.md
    ↓
按 AGENTS 路由，只加载本任务相关 Rule / Skill
    ↓
读取相关总体设计 / 当前阶段文档
    ↓
读取相关真实代码、配置、测试和 workflow
    ↓
恢复当前状态并开始讨论 / 计划 / 实施
```

不要默认读取所有文档、所有 Rule、所有 Skill。

原则：

> 优化目标不是让 AI 读更多，而是让 AI 准确知道此刻该读什么。

## 6. 当前资料归类

### ChatGPT Project 资料

| 当前资料 | 当前处理 | 后续方向 |
|---|---|---|
| `00-START-HERE.md` | 保留 | 继续作为 Bootstrap；动态版本信息不作为最终事实 |
| `01-项目架构与仓库基线.md` | 暂保留 | 作为启动快照；技术版本和目录状态以 GitHub 为准 |
| `02-云端运行与预览方案.md` | 暂保留 | 成熟执行步骤未来优先沉淀为 repo Skill |
| `03-测试与验收标准.md` | 暂保留 | 核心原则可进入 Rule，操作步骤进入验证 Skill |
| `04-已知问题与踩坑记录.md` | 暂保留 | 重复性运行问题未来可进入 Skill references / repo troubleshooting |
| `05-AI工作约定.md` | 暂保留 | 内容最终由 AGENTS + Rules + Skills 承担，避免长期双份维护 |
| `推进目标编写规则.md` | 候选迁移 | 优先迁入项目级 Rule，并压缩为可执行版本 |

当前阶段不急于删除或重写 Project Sources。

### GitHub 当前资料

| 当前资料 | 结论 |
|---|---|
| 后端 `AGENTS.md` | 保留，后续进一步收敛为 Router |
| 前端 `AGENTS.md` | 保留，后续进一步收敛为前端 Router |
| 两仓 `.agents/rules/git-workflow.md` | 规则本身合理；后续明确 canonical / mirror 关系 |
| `.agents/skills/` | 当前不存在，不为了形式先创建 |
| 后端 `doc/ai/00-全局设计/00` | 长期总体设计 canonical |
| 后端 `doc/ai/00-全局设计/01` | 全局能力与当前实现状态索引 |
| 后端 `doc/ai/00-全局设计/02` | 长期关键设计决策摘要 |
| 后端阶段文档 / 14～17 | 阶段状态、讨论、方案、计划和验收记录 |

## 7. 第一轮实际调整范围

职责地图确认后，第一轮只做高收益、低风险调整：

1. 收敛两个 `AGENTS.md` 的 Reading Order 与任务路由。
2. 把“推进目标编写规则”迁入后端项目级 Rule，并压缩重复内容。
3. 明确两仓 `git-workflow.md` 的 canonical / mirror 关系。
4. 从现有运行与验收资料中提炼 `fullstack-validation` Skill，但只保留真正可执行步骤和必要 troubleshooting。
5. Project Sources 暂不删除；待 repo 侧实际运行一段时间后，再决定是否精简。

第一轮不做：

- 大规模重写 `doc/ai`。
- 为所有开发场景建立 Rule。
- 建立大量 Skills。
- 新建第三个“管理仓库”。
- 把当前阶段状态写入 `AGENTS.md`。
- 同时维护两份总体架构。

## 8. 判断标准

后续每新增一份 AI 协作资料，都先回答：

1. 这是稳定入口、约束、工作流、架构，还是当前状态？
2. 是否已有 canonical source？
3. AI 是否真的需要在多数任务中读取它？
4. 能否通过 Router 按需加载，而不是进入全局上下文？
5. 删除重复内容后是否仍能正确工作？
6. 未来修改时能否只改一处？

如果无法明确回答，不应急于新增文件。
