# AI 助手 Phase 3 / Phase 3.5 回归稳定性收口实施与验收记录

## 1. 对应诉求与工作线

本记录对应：

- 方案与计划：`27-AI助手Phase3与Phase3.5回归稳定性收口方案及实施计划.md`
- Backend：`ShanySky/RuoYi-Vue`
- Frontend：`ShanySky/RuoYi-Vue3`
- 专项分支：`chatgpt/ai-agent-regression-stability-closure`

本轮目标不是只消除三个页面报错，而是：

1. 从根因修复模型能力检测超时、聊天 Tool 执行数据库报错、历史会话恢复数据库报错。
2. 扩查同类 Schema / workflow / timeout 漂移。
3. 回归 Phase 3.5、第三阶段以及更早阶段中当前仍保留的主要能力。
4. 通过真实 MySQL、Redis、Spring Boot、Vue3、Browser、Agent / Tool Loop 与真实 GPT 验收后再收口。

## 2. 三个已知问题的最终根因

### 2.1 模型能力检测前端超时，但刷新后 reasoning 已出现

根因是前后端耗时语义不一致，而不是检测结果没有保存。

Frontend 公共 Axios 默认超时为 10 秒；原 `src/api/ai/config.js` 的模型能力检测没有覆盖该超时。

Backend `AiModelCapabilityService.detectCapabilities()` 是同步完整探测：

- Tool Calling 1 次。
- reasoning 基础调用 1 次。
- 再逐个探测 `none / minimal / low / medium / high / xhigh / max`。

因此完整探测可能明显超过 10 秒。浏览器先超时后，Backend 仍继续执行并持久化能力结果；刷新后重新读取数据库即可看到 reasoning 下拉框，这与现场现象一致。

### 2.2 聊天执行页面 Tool 报 `Unknown column 'capability_protocol'`

### 2.3 点击“我的会话”历史记录出现同类异常

这两个现象属于同一根因。

当前 Java Mapper 已按 Phase 3.5 P0 正式契约读取：

- `ai_pending_tool_call.capability_protocol`
- `ai_pending_tool_call.page_id`

当前 canonical fresh-install 和 ordered migration 都已经包含这两个字段。

但 `AI Agent Public Preview` 仍然用历史基线：

- `sql/ai_20260918.sql`
- `sql/ai_hardening_b1_b3_20260919.sql`

初始化一个全新临时数据库，却没有继续执行 Phase 3.5 ordered migration。

结果是 Public Preview 运行了“当前 Java 代码 + 旧数据库 Schema”。聊天 Tool / Pending Tool 和历史会话详情都会进入 `AiPendingToolCallMapper`，从而触发同一个缺字段异常。

扩查还发现 `AI Agent Real GPT Acceptance` 也仍使用旧 AI 初始化脚本；虽然原验收路径未触发该异常，但同样属于环境 Schema 漂移风险。

## 3. 实际修复

### 3.1 Frontend：按操作类型设置显式 timeout

保留普通业务 API 的全局 10 秒超时，不扩大整个系统等待时间。

`src/api/ai/config.js` 增加：

- AI 远端普通操作：45 秒。
- 完整能力 / reasoning 检测：180 秒。

覆盖：

- 测试模型加载。
- 发现远端模型。
- 添加模型。
- 单模型聊天测试。
- Tool Calling 测试。
- 完整能力检测。
- reasoning 检测。

自动检测和手工“重新检测”走同一个已修复接口，因此同时解决。

### 3.2 Backend workflow：新环境只使用 canonical fresh-install

`AI Agent Public Preview`：

- 改为 `backend/sql/ai_fresh_install.sql`。

`AI Agent Real GPT Acceptance`：

- 改为 `sql/ai_fresh_install.sql`。

已有数据库升级仍保持 ordered migrations，不把 fresh-install 当升级脚本使用。

### 3.3 Schema 防回归

`scripts/ci/ai-schema-acceptance.sh` 原有能力继续保留：

- fresh install。
- published legacy baseline -> ordered migration。
- migration 重复执行安全性。
- legacy pending / resolved 数据处理。
- fresh 与 upgrade 最终全部 AI 表 columns / indexes diff 必须一致。

本轮额外加入 workflow schema-entry guard：

- Public Preview 必须使用 canonical fresh-install。
- Real GPT Acceptance 必须使用 canonical fresh-install。
- 两者不得重新使用历史 AI baseline 初始化新数据库。

### 3.4 模型检测超时自动回归

Fullstack deterministic Provider 的 capability probe 被刻意增加延迟，使一次完整 capability detection 稳定超过 Frontend 原全局 10 秒阈值。

最终 Browser E2E 中实际两次检测耗时约：

- 13,868 ms
- 13,869 ms

在超过 10 秒后：

- 自动添加模型流程仍等待检测完成。
- reasoning capability 成功保存。
- reasoning 下拉框直接出现。
- Browser E2E 继续完成后续全部场景。

因此这不是只检查常量，而是真正证明了用户遇到的“超过 10 秒”场景已经被覆盖。

## 4. 同类问题扩查结论

执行工作流检查后，发现会初始化 AI 数据库的临时真实环境中：

- Fullstack E2E 原本已经使用 `ai_fresh_install.sql`，无漂移。
- Backend CI 原本已经使用 `ai_fresh_install.sql`，并执行 fresh + upgrade schema acceptance。
- Public Preview 存在漂移，本轮修复。
- Real GPT Acceptance 存在同类潜在漂移，本轮一并修复。

未发现第三个仍在执行且以历史 AI SQL 作为新数据库入口的 GitHub Actions workflow。

数据库层不是只补了 `capability_protocol` 一个字段，而是继续以“fresh 与 ordered-upgrade 最终全部 AI 表 columns / indexes 一致”为验收条件。

## 5. 保留功能回归范围

本轮没有按历史页面机械恢复旧能力，而是依据当前有效设计和历史验收记录回归仍保留的能力。

第一阶段正式记录明确：

> 对话采用同步 HTTP Turn；暂未做 token-by-token SSE 输出。

因此 SSE / token-by-token 流式输出不是历史已存在的保留功能，本轮不把它误判为回归缺失，也没有为了“回归”新增第二套流式协议。

最终 Fullstack Browser E2E 连续通过以下 31 组主场景及扩展场景：

- 我的 AI 设置个人偏好边界、正式 AI 管理入口。
- Provider / 远端模型目录 / 系统模型添加。
- 新模型自动 Tool Calling + reasoning 检测。
- reasoning `none / low / medium / high / xhigh / max` 以及 unsupported `minimal`。
- 单模型连接测试、模型高级运行参数。
- Pending Tool Result 保持原模型 / 原 reasoning。
- 非模态小窗、Dock、快捷键、字体。
- 同 Conversation 切模型 / reasoning。
- 用户管理真实 Agent Tool Loop。
- WRITE 确认、危险操作服务端 Policy。
- 自动压缩 / Checkpoint。
- Stop、双 Esc、Steering、快速连续 Steering。
- confirmed WRITE 不重复、pending WRITE Steering/F5 安全取消。
- 跨页面 Agent。
- F5 恢复 Conversation / 历史 / draft。
- “我的会话”历史搜索、点击恢复、撤销恢复、重命名。
- Logout / re-login / 新浏览器上下文恢复账号偏好。
- 多 Tab 与同 Conversation 单活动 Run。
- 默认模型失效 fallback。
- System / Compaction Prompt 版本与实际生效。
- Page Capability 服务端开关。
- 权限隔离、Audit、Prompt 不得绕过 Tool Policy。
- 会话归档 / 删除 / 保留策略。
- AI 管理正式页面。
- system / monitor / generator 多模块语义 Page Capability。
- 用户管理完整可逆业务生命周期及安全可编辑字段。

最终浏览器日志：

`AI_AGENT_MODEL_SELECTION_E2E_OK`

## 6. 专项分支真实验收证据

### 6.1 Frontend CI

- Workflow：`AI Agent Frontend CI`
- Run：#250
- Run ID：`35503534986`
- 结果：SUCCESS
- Frontend revision：`0da2e6d3688dda6c503b5327d847385a21384108`

通过：

- Capability Contract。
- AI settings boundary contract。
- npm install。
- Vue3 / Vite production build。

### 6.2 Backend CI

- Workflow：`AI Agent Backend CI`
- 最终专项验证 Run：#392
- Run ID：`35503667231`
- 结果：SUCCESS
- Backend revision：`dab28e10b09b9ce8c6ead9be88ddb26fbb19858d`

通过：

- fresh-install + ordered-upgrade schema acceptance。
- MySQL 8。
- Redis。
- focused AI tests。
- Maven build。
- Spring Boot 实际启动。
- Provider / Model / Agent API。
- Tool Call / Tool Result。
- B1-B3 hardening acceptance。
- Phase 3.5 P0 invariant acceptance。

### 6.3 Fullstack Browser E2E

- Workflow：`AI Agent Fullstack E2E`
- Run：#187
- Run ID：`35503667276`
- 结果：SUCCESS
- Backend revision：`dab28e10b09b9ce8c6ead9be88ddb26fbb19858d`
- Frontend revision：`0da2e6d3688dda6c503b5327d847385a21384108`

关键结果：

- Browser closed-loop acceptance：SUCCESS。
- Persistence / audit redaction / Agent state：SUCCESS。
- 模型 capability detection 实际耗时约 13.8 秒仍成功完成。
- 历史会话点击恢复场景通过。
- 用户管理真实 Tool / WRITE 场景通过。
- Backend artifact 中没有 `Unknown column 'capability_protocol'` 或 `SQLSyntaxErrorException`。

专项过程中早期 Run #186 因新增 slow-probe 测试 fixture 的 Python 缩进错误失败；该问题属于本轮新增测试代码，不是产品回归。修正 fixture 后 Run #187 完整通过。

### 6.4 Real GPT Acceptance

- Workflow：`AI Agent Real GPT Acceptance`
- Run：#9
- Run ID：`35503561796`
- 结果：SUCCESS
- 实际模型：`gpt-5.6-luna`
- reasoning：`low`

真实 Provider 证据：

- reasoning capability confirmed：true。
- cache read tokens：2816。
- Checkpoint：1。
- checkpoint preserved marker：true。
- continuation preserved marker：true。
- Runtime 恢复为 64K / 75%。

该 Run 已使用 canonical `ai_fresh_install.sql`，证明 workflow Schema 修复没有破坏真实 GPT 链路。

### 6.5 Public Preview

- Workflow：`AI Agent Public Preview`
- Run：#16
- Run ID：`35503560533`
- Backend revision：`5a66089a73c75adb70dbae9e445950cb0565d479`
- Frontend revision：`0da2e6d3688dda6c503b5327d847385a21384108`

通过步骤：

- MySQL / Redis 初始化。
- canonical `ai_fresh_install.sql`。
- Backend build / start。
- Frontend install / start。
- Cloudflare Quick Tunnel。
- `Verify public preview`：SUCCESS。
- diagnostics artifact 上传：SUCCESS。

专项验收临时 URL：

`https://temperatures-friends-serves-joyce.trycloudflare.com`

该 URL 仅用于专项人工/公网窗口，生命周期由对应 Runner 决定，不作为正式固定入口。

## 7. 对三个原始 BUG 的验收结论

### BUG 1：能力检测总是前端超时

已从 timeout 根因修复。

自动能力检测和手工重新检测均使用长任务专用 timeout；Fullstack 故意把完整检测拖到约 13.8 秒后仍成功，直接覆盖原 10 秒失败条件。

### BUG 2：聊天中 AI 执行页面操作报 `capability_protocol` 缺字段

已从 Public Preview Schema 漂移根因修复。

canonical fresh-install 已包含正式 Phase 3.5 Tool/Capability 字段，Browser 真实 Tool Loop / Pending Tool / WRITE 全部通过。

### BUG 3：点击“我的会话”历史会话报同类异常

与 BUG 2 是同一 Schema 根因，已一并修复。

Fullstack E2E 的“History entry is opt-in; restore and undo preserve the previous conversation state”真实点击历史会话并恢复成功，未再出现 SQL 字段异常。

## 8. 收口前结论

截至专项分支最终验收：

- 三个现场问题已明确根因并修复。
- 同类 workflow Schema 漂移已扩查并修复。
- fresh install / ordered upgrade 均有自动证明。
- Phase 1 / Phase 2 / Phase 3 / Phase 3.5 当前仍保留的主要主链路已由现有 Fullstack E2E 完整回归。
- 没有为了“历史兼容”恢复已经正式删除、替换或迁移的能力。
- 没有新增第二套 AI Framework、DOM Selector、坐标控制或任意 JavaScript Agent。
- 权限、WRITE 确认、Tool Policy、Run 状态仍由正式代码和若依权限体系保证。

下一步只执行 Git / PR 收口，并在合入 `chatgpt/ai-agent-assistant` 后对主线 revision 再运行关键验收；主线复验结果追加到本记录。


## 9. AI 主线合入与最终复验

### 9.1 PR 收口

Frontend：

- PR：`ShanySky/RuoYi-Vue3#11`
- 合并方式：squash。
- AI 主线 revision：`00fdcc36fc5e269cd41e1ea5803decbcafc91dee`

Backend：

- PR：`ShanySky/RuoYi-Vue#20`
- 合并方式：squash。
- AI 主线产品 revision：`7426f09a66f058f037389c9dd8b28e4b2815b145`

Frontend 先合入，再合入 Backend，保证 Backend 主线触发 Fullstack E2E 时能够配对到已经包含 timeout 修复的 Frontend 主线。

### 9.2 Frontend 主线复验

- Workflow：`AI Agent Frontend CI`
- Run：#251
- Run ID：`35504096864`
- Revision：`00fdcc36fc5e269cd41e1ea5803decbcafc91dee`
- 结果：SUCCESS

Capability Contract、AI settings boundary contract 与 production build 均通过。

### 9.3 Backend 主线复验

- Workflow：`AI Agent Backend CI`
- Run：#393
- Run ID：`35504118901`
- Revision：`7426f09a66f058f037389c9dd8b28e4b2815b145`
- 结果：SUCCESS

其中 fresh-install / ordered migration schema acceptance、MySQL、Redis、focused AI tests、Backend build、Provider / Agent API、B1-B3 与 Phase 3.5 P0 invariants 均通过。

### 9.4 Fullstack 主线复验

- Workflow：`AI Agent Fullstack E2E`
- Run：#188
- Run ID：`35504118902`
- 结果：SUCCESS
- Backend：`7426f09a66f058f037389c9dd8b28e4b2815b145`
- Frontend：`00fdcc36fc5e269cd41e1ea5803decbcafc91dee`

主线 artifact 再次确认：

- Browser closed-loop acceptance：SUCCESS。
- `AI_AGENT_MODEL_SELECTION_E2E_OK`。
- 两个模型完整 capability detection 实际耗时约 13,917 ms / 13,921 ms，超过原 Frontend 10 秒公共 timeout 后仍成功。
- 历史会话恢复、真实 Agent Tool Loop、WRITE、Run、Page Capability、Prompt / Compaction / Audit 等场景全部通过。
- artifact 中无 `Unknown column`、`SQLSyntaxErrorException` 或 `capability_protocol` 缺字段异常。

### 9.5 Real GPT 主线复验

- Workflow：`AI Agent Real GPT Acceptance`
- Run：#10
- Run ID：`35504118913`
- Revision：`7426f09a66f058f037389c9dd8b28e4b2815b145`
- 结果：SUCCESS

真实证据：

- Model：`gpt-5.6-luna`
- Reasoning：`low`
- reasoning capability confirmed：true
- cache read tokens：2816
- Checkpoint：1
- checkpoint / continuation preserved：true
- Runtime reset：64K / 75%

### 9.6 Public Preview 主线复验

- Workflow：`AI Agent Public Preview`
- Run：#17
- Run ID：`35504118905`
- Backend：`7426f09a66f058f037389c9dd8b28e4b2815b145`
- Frontend：`00fdcc36fc5e269cd41e1ea5803decbcafc91dee`
- `Verify public preview`：SUCCESS
- canonical `ai_fresh_install.sql` 初始化：SUCCESS

本次主线临时公网预览：

`https://touring-performance-madonna-pastor.trycloudflare.com`

该地址仅在对应 GitHub Actions Runner keep-alive 生命周期内有效。

## 10. 最终回到用户原始诉求

本轮最终结论：

1. 模型加入系统 / 手工重新检测 reasoning 时的 10 秒 Frontend timeout 根因已修复，并通过约 13.9 秒真实 Browser 请求证明。
2. 聊天执行页面 Tool 与点击历史会话的 `capability_protocol` SQL 异常属于同一个 Public Preview Schema 漂移根因；已改为 canonical fresh schema，并增加 CI 防回归。
3. 同类 Real GPT workflow Schema 漂移也已一并清理。
4. fresh install 与旧数据库 ordered upgrade 最终 AI Schema 一致性已自动验证。
5. Phase 1 / Phase 2 / Phase 3 / Phase 3.5 当前仍保留的主要功能已通过主线 Fullstack Browser E2E 回归。
6. 已经正式删除、迁移或从未实现的能力没有被错误“恢复”；例如第一阶段正式记录即明确采用同步 HTTP Turn，并未实现 token-by-token SSE。
7. 修复已经进入前后端 `chatgpt/ai-agent-assistant`，并在合入后的真实主线 revision 上完成 Backend / Frontend / Browser / Real GPT / Public Preview 复验。

因此，本轮“修复三个现场 BUG，并确认此前正常且当前仍保留的主要功能没有被 Phase 3 / Phase 3.5 改造破坏”的原始诉求已经完成。
