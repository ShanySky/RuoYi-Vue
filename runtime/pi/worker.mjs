import { createInterface } from 'node:readline'
import { resolve } from 'node:path'
import { createAgentSession, ModelRuntime, SessionManager, SettingsManager, DefaultResourceLoader } from '@earendil-works/pi-coding-agent'

// 本进程仅运行锁定的框架和授权回调，绝不执行模型给出的宿主命令。
const MAX_PACKET = 2 * 1024 * 1024
const EMPTY_USAGE = { inputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 0 }
let current, runtimeConfig, session, pending, lastAssistant, unsettledUsage = EMPTY_USAGE
let started = false, settled = false, turns = 0
let cacheFallbackUsed = false, cacheUnsupported = false
let rejectedTool
const send = value => {
  const encoded = JSON.stringify(value)
  if (Buffer.byteLength(encoded) > MAX_PACKET) throw new Error('协议输出超限')
  process.stdout.write(encoded + '\n')
}
const fail = error => {
  if (!settled) {
    settled = true
    send({ type: 'error', code: rejectedTool === undefined ? 'HARNESS_FAILED' : 'UNAPPROVED_TOOL', toolName: rejectedTool })
  }
  session?.agent.abort()
  process.exitCode = 1
  setTimeout(() => process.exit(1), 50).unref()
}
const consumeUsage = () => {
  const value = unsettledUsage
  unsettledUsage = EMPTY_USAGE
  return value
}
const textOf = message => message.content.filter(part => part.type === 'text').map(part => part.text).join('\n')
const history = () => current.messages.filter(message => message.role !== 'SYSTEM').map(message => {
  const timestamp = Date.now()
  if (message.role === 'USER') return { role: 'user', content: message.content || '', timestamp }
  if (message.role === 'TOOL') return { role: 'toolResult', toolCallId: message.toolCallId, toolName: message.toolName,
    content: [{ type: 'text', text: message.content || '{}' }], isError: false, timestamp }
  return { role: 'assistant', content: [
    ...(message.content ? [{ type: 'text', text: message.content }] : []),
    ...(message.toolCalls || []).map(call => ({ type: 'toolCall', id: call.id, name: call.name, arguments: JSON.parse(call.arguments || '{}') }))
  ], api: 'openai-completions', provider: 'ruoyi', model: runtimeConfig.model,
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: message.toolCalls?.length ? 'toolUse' : 'stop', timestamp }
})
const systemPrompt = () => current.messages.filter(message => message.role === 'SYSTEM').map(message => message.content || '').join('\n\n')
const callbacks = () => current.tools.map(tool => ({
  name: tool.name, label: tool.name, description: tool.description,
  // 参数由若依统一校验并持久记录失败，避免框架内部重试绕过运行限额和审计。
  // 发给模型的真实约束在 onPayload 中取自同一份获批工具定义。
  parameters: { type: 'object', additionalProperties: true },
  async execute(id, args, signal) {
    if (pending || signal?.aborted || settled) throw new Error('回调状态无效')
    const receipt = new Promise((resolveReceipt, reject) => {
      const aborted = () => { pending = null; reject(new Error('运行已停止')) }
      signal?.addEventListener('abort', aborted, { once: true })
      pending = { id, resolve: value => {
        signal?.removeEventListener('abort', aborted)
        pending = null
        resolveReceipt(value)
      } }
    })
    send({ type: 'tool', bridgeId: id, name: tool.name, arguments: JSON.stringify(args),
      text: lastAssistant ? textOf(lastAssistant) : '', usage: consumeUsage() })
    const result = await receipt
    return { content: [{ type: 'text', text: result }], details: {} }
  }
}))

async function start(packet) {
  if (started) throw new Error('不能重复启动')
  started = true
  current = packet.request
  runtimeConfig = packet.runtime
  const root = process.cwd()
  const runtime = await ModelRuntime.create({ authPath: resolve(root, 'auth.json'), modelsPath: null,
    modelsStorePath: resolve(root, 'models-cache.json'), allowModelNetwork: false, refreshOnCreate: false })
  runtime.registerProvider('ruoyi', { baseUrl: runtimeConfig.baseUrl, api: 'openai-completions', apiKey: runtimeConfig.apiKey,
    models: [{ id: runtimeConfig.model, name: runtimeConfig.model, reasoning: false, input: ['text'],
      contextWindow: runtimeConfig.contextWindow, maxTokens: runtimeConfig.outputLimit,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, compat: { supportsDeveloperRole: false } }] })
  // 注册到内存凭据存储后不在本适配器状态中保留明文副本。
  delete runtimeConfig.apiKey
  const settings = SettingsManager.inMemory({ compaction: { enabled: false },
    retry: { enabled: false, provider: { maxRetries: 0, timeoutMs: runtimeConfig.timeoutMs } },
    enableInstallTelemetry: false, enableAnalytics: false })
  const resources = new DefaultResourceLoader({ cwd: root, agentDir: root, settingsManager: settings,
    noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
    systemPromptOverride: systemPrompt })
  await resources.reload()
  const initialTools = callbacks()
  const created = await createAgentSession({ cwd: root, agentDir: root, modelRuntime: runtime,
    model: runtime.getModel('ruoyi', runtimeConfig.model), thinkingLevel: 'off',
    tools: initialTools.map(tool => tool.name), customTools: initialTools, resourceLoader: resources,
    settingsManager: settings, sessionManager: SessionManager.inMemory(root) })
  session = created.session
  session.agent.toolExecution = 'sequential'
  session.agent.state.messages = history()
  session.agent.state.systemPrompt = systemPrompt()
  session.agent.state.tools = initialTools
  session.agent.onPayload = payload => {
    if (++turns > 17 || settled) throw new Error('模型轮次超限')
    payload.tools = current.tools.length ? current.tools.map(tool => ({ type: 'function', function: {
      name: tool.name, description: tool.description, parameters: JSON.parse(tool.inputSchemaJson)
    } })) : undefined
    payload.parallel_tool_calls = false
    payload.max_completion_tokens = runtimeConfig.outputLimit
    delete payload.max_tokens
    payload.prompt_cache_key = cacheFallbackUsed ? undefined : current.promptCacheKey || undefined
    if (current.reasoningEffort) payload.reasoning_effort = current.reasoningEffort
    return payload
  }
  // 若依先重新裁决授权、组装检查点和回执，再释放上一个异步工具。
  session.agent.prepareNextTurnWithContext = () => {
    const tools = callbacks()
    const messages = history()
    session.agent.state.messages = messages
    session.agent.state.tools = tools
    return { context: { systemPrompt: systemPrompt(), messages, tools } }
  }
  session.agent.subscribe(event => {
    if (event.type !== 'message_end' || event.message.role !== 'assistant') return
    lastAssistant = event.message
    const usage = event.message.usage || {}
    unsettledUsage = { inputTokens: (usage.input || 0) + (usage.cacheRead || 0),
      cacheReadTokens: usage.cacheRead || 0, cacheWriteTokens: usage.cacheWrite || 0, totalTokens: usage.totalTokens || 0 }
    if (event.message.stopReason === 'error' && current.promptCacheKey && !cacheFallbackUsed) {
      const message = String(event.message.errorMessage || '').toLowerCase()
      if (/prompt[_ -]cache[_ -]key/.test(message) && /unsupported|unknown|unrecognized|not allowed|extra_forbidden|unexpected field/.test(message)) {
        cacheUnsupported = true
        return
      }
    }
    if (['error', 'aborted', 'length'].includes(event.message.stopReason)) throw new Error('模型未完整完成本轮')
    const available = new Set(current.tools.map(tool => tool.name))
    for (const call of event.message.content.filter(part => part.type === 'toolCall')) {
      if (!available.has(call.name)) { rejectedTool = String(call.name).slice(0, 80); throw new Error('工具未获批') }
      if (!call.arguments || Array.isArray(call.arguments) || typeof call.arguments !== 'object') throw new Error('工具参数不是对象')
    }
  })
  await session.agent.continue()
  // 明确拒绝可选缓存参数时，仅重试一次模型请求，不重放任何工具；普通错误不自动重试。
  if (cacheUnsupported && !settled) {
    cacheFallbackUsed = true
    session.agent.state.messages = history()
    await session.agent.continue()
  }
  if (!settled) {
    if (!lastAssistant || pending || ['error', 'aborted'].includes(lastAssistant.stopReason)) throw new Error('框架异常结束')
    settled = true
    send({ type: 'result', text: textOf(lastAssistant), usage: consumeUsage(), turns })
    session.dispose()
  }
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  try {
    if (Buffer.byteLength(line) > MAX_PACKET) throw new Error('协议输入超限')
    const packet = JSON.parse(line)
    if (packet.type === 'start') void start(packet).catch(fail)
    else if (packet.type === 'resume') {
      if (!pending || pending.id !== packet.bridgeId || settled) throw new Error('回执归属无效')
      const result = packet.request.messages.at(-1)
      if (result?.role !== 'TOOL') throw new Error('缺少若依已接受的工具结果')
      current = packet.request
      pending.resolve(result.content || '{}')
    } else if (packet.type === 'cancel') {
      settled = true
      session?.agent.abort()
      process.exit(0)
    } else throw new Error('未知协议动作')
  } catch { fail() }
})
input.on('close', () => { session?.agent.abort(); process.exit(0) })
process.on('uncaughtException', fail)
process.on('unhandledRejection', fail)
setTimeout(() => { session?.agent.abort(); process.exit(1) }, 30 * 60 * 1000).unref()
