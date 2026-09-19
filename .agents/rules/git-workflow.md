# Git 工作流规则

> 类型：项目级 Rule
>
> Canonical source：`ShanySky/RuoYi-Vue/.agents/rules/git-workflow.md`
>
> 前端仓库 `ShanySky/RuoYi-Vue3` 保留同路径 mirror，保证只打开前端仓库时也能独立工作。项目级语义发生变化时，以本文件为准并同步 mirror。

本规则适用于 RuoYi AI 项目的架构讨论、专项开发、测试修复和主线合并。

## 1. 分支职责

- `master` 是干净基线，日常 AI 开发不要直接落到 master。
- `chatgpt/ai-agent-assistant` 是已经成熟、已经验收成果的稳定 AI 开发主线。
- 较大的架构讨论、专项实现、迁移和高风险改造应从最新 AI 主线创建独立工作分支。
- 跨前后端任务需要修改两个仓库时，优先在两个仓库使用语义一致的工作分支名称。

原则：

> 工作分支可以保留开发过程，稳定主线只保留长期有意义的历史。

## 2. 实施分支允许细粒度提交

实施过程中可以为了开发、调试、回滚和验证进行小步提交，例如：

- 初始实现。
- 局部重构。
- Bug 修复。
- 测试补充。
- CI 修复。
- 验收记录。

不要为了让过程历史“漂亮”而减少必要的安全检查和中间提交。

## 3. 合入 AI 主线前必须整理历史

专项实施完成并通过验收后，不要把几十个过程性提交直接 fast-forward 或原样合入 `chatgpt/ai-agent-assistant`。

合并前应按照长期有意义的功能边界进行 `squash` / `fixup` / rebase 或等价的 Git 历史整理。

一个自然实施里程碑通常保留约 3～6 个语义清晰的提交即可。提交数量不是硬指标，判断标准是每个提交是否代表值得长期保留的完整目的。

例如一个包含三个主要目标的实施任务，可以整理成：

1. `feat(ai): implement <goal-1>`
2. `feat(ai): implement <goal-2>`
3. `feat(ai): implement <goal-3>`
4. `test(ai): validate <milestone>`
5. `docs(ai): record <milestone>`

不要机械地把所有内容压成一个提交，也不要保留大量只有开发过程价值的临时提交。

## 4. 推荐的历史整理方式

根据实际历史选择合适的标准 Git 手段，例如：

- `git rebase -i` + `squash` / `fixup`。
- `git reset --soft` / `git reset --mixed` 后按语义重新暂存和提交。
- `git add -p` 按 hunk 拆分交叉修改。
- 在安全的临时整理分支上重新构造少量语义提交。

历史整理是 Git 操作，不应为了整理 commit 而重新开发已经验收通过的业务代码。

## 5. 改写已推送历史时的安全要求

如果确实需要改写已经推送的共享历史：

1. 先重新确认目标分支 HEAD 没有出现新的未知提交。
2. 先创建 archive 分支或 tag 保留旧历史。
3. 在临时整理分支完成新历史。
4. 如果目标只是整理历史，必须验证整理前后的最终代码树完全一致；优先比较 commit tree SHA。
5. 重新检查测试和验收结论仍适用于最终代码树。
6. 只有上述条件满足后，才允许使用受控的强制引用更新；本地 Git 操作优先使用 `--force-with-lease`，不要无保护地覆盖共享分支。
7. 所有基于旧主线继续工作的分支都要同步 rebase / replay 到新主线。

## 6. 合并前检查

进入 AI 主线前至少确认：

- 工作分支基于当前最新 AI 主线，或已经处理与主线的新变化。
- 实施范围与已确认架构边界一致，没有顺手带入未批准的大改造。
- 必要的编译、测试、真实运行或端到端验收已经通过。
- 提交历史已经整理到适合长期维护的粒度。
- 没有 Token、密码、临时调试文件或无关改动。
- 文档、代码和验收记录不存在明显互相矛盾的状态。

## 7. 主线历史原则

`chatgpt/ai-agent-assistant` 应让后来的人或 AI 通过 `git log` 快速理解：

- 这一轮解决了什么问题。
- 哪些是主要功能或架构目标。
- 哪个提交负责测试 / 验收。
- 哪个提交记录最终状态。

不要让长期主线变成调试过程日志。
## 8. 分支生命周期与清理

专项实施、架构讨论或临时治理分支在完成使命后，应及时清理；除明确标记为 `archive/*` 或其他长期保留用途的分支外，不长期保留已经完成的工作分支。

优先顺序：

1. 正常实施尽量通过 Pull Request 合入 AI 主线，并在仓库开启“Automatically delete head branches”，由 GitHub 在 PR 合并后自动删除 head branch。
2. 如果 GitHub Connector 当前没有 `delete_branch` / `delete_ref` 能力，又存在需要主动清理的遗留分支，可以使用**临时 GitHub Actions 清理分支**作为 fallback，不要求用户手工删除。

使用临时 GitHub Actions 清理时必须遵守：

1. 先确认目标工作分支的有效成果已经完整合入目标主线；未合入、仍需保留或用途不明确的分支禁止删除。
2. 从当前主线 HEAD 创建临时清理分支，例如 `chatgpt/cleanup-<task>`。
3. 临时 workflow 只存在于该清理分支，不写入主线；权限只申请：

   ```yaml
   permissions:
     contents: write
   ```

4. Runner 使用自带 `gh` CLI 和当前仓库 `GITHUB_TOKEN` 删除目标 ref：

   ```yaml
   env:
     GH_TOKEN: ${{ github.token }}
     REPOSITORY: ${{ github.repository }}
   ```

   ```bash
   gh api --method DELETE \\
     "repos/$REPOSITORY/git/refs/heads/<目标分支>"
   ```

5. 目标分支删除成功后，同一个 workflow 再删除自己的临时清理分支：

   ```bash
   gh api --method DELETE \\
     "repos/$REPOSITORY/git/refs/heads/<临时清理分支>"
   ```

6. workflow 可监听临时清理分支自身的 push，使创建该 workflow 的提交直接触发执行；不要为了清理分支在主线长期保留专用 workflow。
7. 执行完成后必须重新查询并确认：
   - 目标工作分支已不存在；
   - 临时清理分支已不存在；
   - 主线 HEAD 没有因为清理动作发生变化。
8. 如果仓库或组织策略使 `GITHUB_TOKEN` 实际没有 `contents: write`，应明确报告清理失败，不得假装成功，也不要擅自扩大到更高权限凭据。

该 fallback 只用于分支生命周期治理，不用于绕过分支保护、审批或其他仓库安全策略。

