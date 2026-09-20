# AI 助手 Phase 3 / Phase 3.5 回归稳定性收口方案及实施计划

## 1. 用户诉求

本轮不是只修三个表面报错，而是完成一次完整的稳定性闭环：

- 修复模型加入系统后能力检测“系统接口请求超时”，但刷新后能力结果又已出现的问题。
- 修复聊天中 AI 执行页面操作时报 `Unknown column 'capability_protocol'`。
- 修复“我的会话”点击历史会话时出现同类数据库异常。
- 沿已知根因排查同类遗漏。
- 对 Phase 3.5 之前及第三阶段之前、当前设计仍保留的主要功能做真实回归。
- 已正式删除、替换或迁移的旧功能不恢复。

完成判断必须重新回到“当前稳定 AI 主线是否真实可用”，不能以代码修改、编译或单次 CI 绿色代替。

## 2. 当前真实起点

本轮从 2026-09-20 执行时最新稳定 AI 主线创建专项分支：

`chatgpt/ai-agent-regression-stability-closure`

起始 revision：

- Backend `ShanySky/RuoYi-Vue`：`e0fe3e912d06148147aa1031cdb93158301a574c`
- Frontend `ShanySky/RuoYi-Vue3`：`f9e497bae629e062d87ad44dcfa5ea94184bab83`

专项分支开始时没有针对 `chatgpt/ai-agent-assistant` 的 open PR。

## 3. 已确认根因

### 3.1 模型能力检测超时

前端通用 Axios 实例的请求超时固定为 10 秒：

`src/utils/request.js -> timeout: 10000`

但“检测模型能力”不是普通业务请求。当前后端 `AiModelCapabilityService.detectCapabilities()` 会同步执行：

1. 1 次 Tool Calling 探测。
2. 1 次不带 reasoning 的基础调用。
3. 依次探测 `none / minimal / low / medium / high / xhigh / max` 7 个 reasoning 值。

也就是说一次完整能力检测最多包含 9 次真实模型调用，每次又服从 Provider 自己的模型请求超时。

因此浏览器可能先在 10 秒处报“系统接口请求超时”，而后端请求线程仍继续完成探测并把 `reasoning_capability / reasoning_efforts` 持久化。随后刷新页面重新读取数据库，就会看到已经出现思考档位。

这与用户观察到的“请求报错，但刷新后结果存在”完全一致。

### 3.2 Tool Call 与历史会话的数据库异常

当前代码 `AiPendingToolCallMapper` 已把以下 Phase 3.5 P0 字段作为正式契约：

- `capability_protocol`
- `page_id`

Canonical fresh-install schema `sql/ai_fresh_install.sql` 已包含这些字段；现有 ordered migration `sql/ai_migrations/20260920_01_phase35_p0_protocol.sql` 也负责从 Phase 3.5 前基线补齐它们。

但是当前 `AI Agent Public Preview` 仍使用旧的：

- `sql/ai_20260918.sql`
- `sql/ai_hardening_b1_b3_20260919.sql`

初始化一个全新数据库，却没有继续执行 Phase 3.5 ordered migration。

因此 Public Preview 运行的是“当前 Java 代码 + 旧数据库 Schema”，一旦历史会话详情或 Tool/Pending Tool 查询进入 `AiPendingToolCallMapper`，就会直接报：

`Unknown column 'capability_protocol' in 'field list'`

聊天执行页面操作与点击历史会话出现同一异常，属于同一个 Schema 漂移根因。

同时检查发现 `AI Agent Real GPT Acceptance` 也仍使用旧 AI 初始化脚本，虽然它当前测试路径没有触发该字段异常，但同样属于环境 Schema 漂移风险。

## 4. 修复方案

### 4.1 长耗时 Provider 操作使用显式前端超时

保持全局普通 API 10 秒超时不变，避免扩大所有业务接口的失败等待时间。

仅对 AI Provider / 模型外部调用使用专用超时：

- 单次远端模型目录、连接、Tool Calling 等操作：使用明显高于普通 API 的专用超时。
- 完整 reasoning / capability detection：使用长任务专用超时，覆盖当前同步多次 Provider 调用。

不把能力检测改成第二套异步任务框架；当前阶段先修正真实超时边界，使现有同步语义与前端等待策略一致。

### 4.2 所有全新 CI / Preview 环境统一使用 canonical fresh-install schema

全新的临时数据库统一使用：

`sql/ai_fresh_install.sql`

不再把历史基线 SQL 叠加当成新环境初始化入口。

既有数据库升级继续走：

`sql/ai_migrations/*.sql`

现有 schema acceptance 继续同时证明：

- fresh install；
- published legacy baseline -> ordered migration；
- migration safe rerun；
- fresh / upgraded 最终 AI 表结构一致。

### 4.3 增加防回归契约

- Schema acceptance 增加对关键 workflow 初始化入口的静态约束，防止 Public Preview / Real GPT 再退回历史 AI SQL。
- Fullstack Browser E2E 的 deterministic Provider 对能力探测请求增加可控延迟，使完整能力检测稳定超过全局 10 秒阈值；这样如果未来删除专用超时，浏览器场景会重新失败。
- 继续复用现有历史会话、Agent / Tool Loop、WRITE、Page Capability、设置、审计等 E2E 场景，不复制第二套回归脚本。

## 5. 实施计划

1. 修复 Frontend AI config API 的 Provider / capability 请求超时边界。
2. 修复 Backend Public Preview 与 Real GPT workflow 的数据库初始化入口。
3. 增强 schema acceptance，固定 workflow 不得使用历史 AI SQL 初始化新环境。
4. 增强 Fullstack deterministic Provider，使模型能力检测真实跨过 10 秒，并由浏览器自动添加模型/自动检测场景证明修复。
5. 在专项分支运行：
   - Frontend CI；
   - Backend CI（含 fresh + upgrade schema acceptance）；
   - paired Fullstack Browser E2E；
   - Real GPT acceptance（若仓库现有凭据仍可用）；
   - Public Preview。
6. 根据失败日志继续修复本轮发现的真实回归，直到主要保留功能通过。
7. 形成实施与验收记录。
8. 合并前重新同步最新 AI 主线并检查并行工作。
9. 前后端通过 PR 收口到 `chatgpt/ai-agent-assistant`，并在主线再次复验关键 workflow。

## 6. 回归范围

主要复用当前 Fullstack E2E 已覆盖并仍属于正式产品的能力：

- Provider / 系统模型添加、自动能力检测、连接测试、默认模型、reasoning。
- 用户个人默认模型 / 默认 reasoning 与系统治理边界。
- 普通聊天、模型切换、同 Conversation reasoning 切换。
- Conversation 创建、历史列表、历史恢复、重命名、刷新恢复。
- Run Stop / 双 ESC / Steering / logout / F5。
- Tool Call / Pending Tool / Tool Result。
- READ / WRITE / DANGEROUS_WRITE 与确认、权限、服务端 Policy。
- Page Capability、page instance/version、跨页导航。
- Prompt / Compaction / Checkpoint。
- 会话策略、会话审计、权限隔离。
- AI 管理正式入口与个人设置职责边界。

已经正式删除、替换或迁移的旧入口不作为回归失败。

## 7. 完成标准

最终必须同时满足：

- 三个已知问题从根因修复。
- Public Preview 不再存在代码 / Schema 漂移。
- fresh install 与 ordered upgrade 均通过自动验收。
- 完整模型能力检测在浏览器侧超过 10 秒时仍能完成并刷新到正确 reasoning 下拉状态。
- 历史会话恢复与 Agent / Tool Loop 在真实 MySQL / Redis / Spring Boot / Vue / Browser 闭环中通过。
- 当前 Fullstack 回归覆盖的第三阶段及 Phase 3.5 保留功能没有新阻断性回归。
- 专项成果最终进入 `chatgpt/ai-agent-assistant`，并对合入后的主线 revision 再做关键复验。
