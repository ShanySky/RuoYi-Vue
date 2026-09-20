# 仓库内 AI 开发资料说明

本目录只保存**与具体 AI 产品无关、对不同开发 Agent 都有价值**的项目约束和可重复工作流。

## 1. 资料职责

- 根目录 `AGENTS.md`：仓库角色、长期开发原则和主要项目入口。
- `.agents/rules/`：稳定、项目级的开发约束。
- `.agents/skills/`：已经重复验证的项目工作流。
- `doc/ai/`：长期架构、关键设计决策、阶段方案、实施计划和验收记录。
- 代码、配置、SQL、测试、CI 和真实运行结果：实现状态的最终事实。

## 2. 当前通用 Rule / Skill

- `.agents/rules/git-workflow.md`：Git 分支、历史整理、合并和清理原则。
- `.agents/rules/progress-goal.md`：推进目标编写规则。
- `.agents/skills/fullstack-validation/SKILL.md`：真实运行、E2E 和全栈验收。

## 3. 维护原则

- 只保存项目本身的稳定事实和通用开发规则。
- 不在仓库中维护 ChatGPT、Codex 或其他特定产品的工具选择、Connector 路由、会话恢复或云端操作提示。
- 不把当前阶段进度、临时 blocker、当前 PR 状态写入 `AGENTS.md` 或 Rule。
- 项目级设计尽量只维护一份 canonical source，不为了目录对称复制平行文档。
- 新增 Rule / Skill 前先确认它确实稳定、重复且对多个开发环境都有价值。
