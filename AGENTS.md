# RuoYi AI 项目代理约定

## 1. 仓库角色

本仓库是 RuoYi AI 助手项目的后端仓库：`ShanySky/RuoYi-Vue`。

配套前端仓库是 `ShanySky/RuoYi-Vue3`。本仓库同时承担跨前后端 AI 项目的 Architecture Repository，长期架构、关键设计决策和阶段文档统一维护在 `doc/ai/`。

项目长期产品目标分三层：

1. AI 理解真实业务并帮助人类用户完成业务。
2. 数字员工在授权范围内自主承担业务。
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
- `doc/ai/05-产品规划与商业化/26-AI助手产品愿景与商业化路线.md`：三层产品愿景、V1 方向、版本演进与商业化路线。

仓库当前代码、配置、SQL、测试、workflow 和真实运行结果始终是实现状态的最终事实来源。

## 4. 五套运行环境

后端统一使用 `local`、`dev`、`ci`、`test`、`prod` 五个 Maven Profile 和同名 Spring Profile：

| 环境 | 配置文件 | 用途 | 版本控制 |
| --- | --- | --- | --- |
| `local` | `ruoyi-admin/src/main/resources/application-local.yml` | 开发者本机联调 | 被 `.gitignore` 精确忽略 |
| `dev` | `ruoyi-admin/src/main/resources/application-dev.yml` | 共享开发环境 | 跟踪 |
| `ci` | `ruoyi-admin/src/main/resources/application-ci.yml` | GitHub Actions、自动化测试和临时预览 | 跟踪 |
| `test` | `ruoyi-admin/src/main/resources/application-test.yml` | 测试环境 | 跟踪 |
| `prod` | `ruoyi-admin/src/main/resources/application-prod.yml` | 正式环境 | 跟踪 |

构建和运行示例中的 `<env>` 必须替换为上述五个环境之一：

```powershell
mvn -P<env> -DskipTests clean package
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=<env>
```

使用和维护时遵守以下约束：

- 项目要求 Java 17。执行 Maven、测试或启动前先确认 `java -version` 与 `mvn -version` 实际使用 Java 17；不要依赖机器全局默认版本。
- 未指定环境时，Maven 与 Spring 默认选择 `local`。非本机运行必须同时明确 Maven 的 `-P<env>` 和运行时的 `--spring.profiles.active=<env>`，避免构建环境与启动环境不一致。
- 本机 `local` 当前依赖 `127.0.0.1:3306` 的 MySQL、数据库 `ruoyi_ai`，以及 `127.0.0.1:6379` 的 Redis；账号和密码只从被忽略的本地配置读取，不写入本文档。
- `dev`、`test`、`prod` 当前保留了环境拆分时的初始连接配置；部署前必须核对并更新对应文件，不能因文件名正确就推断目标服务地址正确。GitHub Actions 固定使用 `ci`。
- Druid 公共连接池配置已经归并到基础 `application.yml`，环境文件只维护数据库、Redis 等差异；不要恢复 `application-druid.yml` 或 `spring.profiles.include: druid`。
- 前端必须选择同名环境。本机联调配套前端执行 `npm run dev:local`，持续集成配套前端执行 `npm run dev:ci` 或 `npm run build:ci`。
- 新库初始化顺序是 `sql/ry_20260417.sql`、`sql/quartz.sql`、`sql/ai_fresh_install.sql`；`sql/ai_fresh_install.sql` 已包含新装所需 AI 结构，不要再叠加旧库升级脚本。

## 5. 当前后端主干

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

## 6. Rule / Skill 的门槛

只有稳定、重复的真实开发场景才新增 Rule 或 Skill：

- Rule：违反会造成风险、返工或架构漂移的稳定约束。
- Skill：已经重复验证、步骤相对稳定的可执行工作流。

不要为了目录完整预建规则体系或空 Skill。
