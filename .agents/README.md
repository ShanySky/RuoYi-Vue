# AI 协作资料分层与读取约定

> 文档类型：AI 开发协作 / 知识分层职责地图
>
> 适用范围：`ShanySky/RuoYi-Vue` + `ShanySky/RuoYi-Vue3`
>
> 本文件定义资料职责，不替代具体架构文档、规则或 Skill。

## 1. 核心原则

- Conversation 是当前工作台，GitHub 是长期事实源。
- ChatGPT Project 只承担 Bootstrap，不维护频繁变化的项目状态。
- `AGENTS.md` 是仓库入口和 Router，不是超级系统提示词。
- Rule 记录稳定约束；Skill 记录可重复执行流程；`doc/ai/` 记录架构、决策和阶段状态。
- 真实代码、配置、测试、CI 和运行结果是实现状态的最终事实。
- 优化目标不是让 AI 读更多，而是让 AI 准确知道此刻该读什么。

## 2. 六层职责

### L0：ChatGPT Project —— Bootstrap

只保留稳定入口信息：项目定位、两个仓库、默认 AI 主线、基本工作原则，以及“开始工作前先检查 GitHub 当前状态”。

不维护当前阶段进度、最近提交、临时 blocker、动态架构结论或频繁演进的 Rule / Skill 正文。

### L1：根 `AGENTS.md` —— Repo Entry Point / Router

负责：

- 本仓库在整个项目中的角色。
- 开发主线和少量核心原则。
- 默认 Reading Order。
- 按任务类型路由到 Rule / Skill / 设计文档。
- 指明跨仓库 canonical source。

不复制完整架构、完整测试流程或当前阶段进度。

### L2：`.agents/rules/` —— 稳定约束

用于回答“在某种明确场景下必须遵守什么”。

只有已经在真实工作中重复出现、违反会造成明显风险或返工、且内容相对稳定的约束才进入 Rule。

### L3：`.agents/skills/` —— 可重复执行工作流

用于回答“遇到一种重复任务，具体怎样完成”。

Rule = 必须遵守的约束；Skill = 可执行的工作流程。只有流程已经重复验证且步骤相对稳定时才抽成 Skill。

### L4：`doc/ai/` —— 架构、决策与项目状态

后端仓库 `ShanySky/RuoYi-Vue` 兼任跨前后端 AI 项目的 Architecture Repository。

项目级 canonical 文档集中在后端，包括总体设计、能力路线图、关键设计决策、阶段方案、实施计划、验收记录和架构复盘。

前端仓库只维护确有必要的前端专属资料，不为了目录对称复制项目级设计。

### L5：当前工作 —— Working State

动态状态放在当前架构讨论文档、实施方案 / 计划、Issue / PR、工作分支和提交中。

例如当前架构复盘中的逐项决策状态属于对应讨论 / 方案文档，不属于 `AGENTS.md`。

### L6：代码、测试与真实运行 —— Implementation Truth

文档与实现冲突时，先确认目标分支，再以当前代码、配置、数据库结构、CI 和真实运行结果判断实现状态；必要时回写文档。

实现事实优先不等于可以静默违反已确认架构。如果代码与已确认设计冲突，应明确指出并处理。

## 3. 两个仓库的职责

### 后端 `ShanySky/RuoYi-Vue`

同时承担：

1. RuoYi AI 后端代码仓。
2. 跨前后端 AI Architecture Repository。
3. 项目级 AI 协作规则与 Skill 的 canonical source。

建议结构：

```text
RuoYi-Vue/
├─ AGENTS.md
├─ .agents/
│  ├─ README.md
│  ├─ rules/
│  └─ skills/
├─ doc/ai/
└─ ruoyi-ai/
```

### 前端 `ShanySky/RuoYi-Vue3`

承担：

1. RuoYi AI 前端代码仓。
2. 前端 Repo Entry Point。
3. 前端专属 Rule / Skill。
4. 为“只打开前端仓库”场景保留必要的项目级规则镜像或明确引用。

不复制总体设计、能力路线图、项目级架构决策和阶段讨论文档。

## 4. 项目级与仓库级规则

项目级规则例如 Git 工作流、推进目标、跨仓库协作、全栈验收，canonical source 优先放后端。

如果只打开前端仓库也必须能工作，可以保留精简镜像，但必须明确 canonical source，避免两份规则独立演进。

仓库级规则只保存在对应仓库，例如后端 Java / Spring / SQL / Agent Runtime 约束，前端 Vue / Page Capability / Tool Registry / UI 约束。

## 5. 默认恢复上下文流程

```text
用户当前目标
    ↓
确认目标仓库、目标分支和 GitHub 当前状态
    ↓
读取目标仓库 AGENTS.md
    ↓
按 AGENTS 路由，只加载本任务相关 Rule / Skill
    ↓
读取相关总体设计 / 当前阶段文档
    ↓
读取相关真实代码、配置、测试和 workflow
    ↓
开始讨论 / 计划 / 实施 / 验收
```

不要默认加载所有文档、所有 Rule、所有 Skill。

## 6. 当前资料处理方向

### ChatGPT Project

- `00-START-HERE.md`：保留为 Bootstrap。
- `01-项目架构与仓库基线.md`：暂保留为启动快照，实时状态以 GitHub 为准。
- `02-云端运行与预览方案.md`：暂保留，成熟执行步骤沉淀到 repo Skill。
- `03-测试与验收标准.md`：暂保留，稳定约束进入 Rule / AGENTS，执行步骤进入 Skill。
- `04-已知问题与踩坑记录.md`：暂保留，重复性问题逐步进入 Skill troubleshooting。
- `05-AI工作约定.md`：暂保留，长期由 AGENTS + Rules + Skills 承担。
- `推进目标编写规则.md`：迁入项目级 Rule。

当前不急于删除或重写 Project Sources。

### GitHub

- 两个 `AGENTS.md`：保留并收敛为 Router。
- `.agents/rules/git-workflow.md`：后端为 canonical，前端保留可独立工作的 mirror。
- `.agents/rules/progress-goal.md`：后端项目级 canonical。
- `.agents/skills/fullstack-validation/SKILL.md`：后端项目级 canonical。
- 后端 `doc/ai/`：项目级 Architecture Repository。

## 7. 新增资料前的判断

每新增一份 AI 协作资料先回答：

1. 它是稳定入口、约束、工作流、架构，还是当前状态？
2. 是否已经存在 canonical source？
3. AI 是否真的需要在多数任务中读取它？
4. 能否通过 Router 按需加载？
5. 删除重复内容后是否仍能正确工作？
6. 未来修改时能否只改一处？

不能明确回答时，不急于新增文件。
