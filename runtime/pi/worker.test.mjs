import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { mkdtempSync, rmSync, readdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const workerPath = fileURLToPath(new URL('./worker.mjs', import.meta.url))
const tool = (name, maximum = 1) => ({ name, description: '确定性受控测试',
  inputSchemaJson: JSON.stringify({ type: 'object', properties: { limit: { type: 'integer', maximum } }, additionalProperties: false }) })
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

async function fixture(answer) {
  const requests = []
  const server = createServer(async (request, response) => {
    let raw = ''
    for await (const chunk of request) raw += chunk
    const body = JSON.parse(raw)
    requests.push(body)
    const output = answer(body, requests.length)
    if (output.status) {
      response.writeHead(output.status, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: output.error } }))
      return
    }
    const delta = typeof output === 'string' ? { role: 'assistant', content: output }
      : { role: 'assistant', tool_calls: [{ index: 0, id: 'm_' + requests.length, type: 'function', function: {
        name: output.name, arguments: JSON.stringify(output.args || {})
      } }] }
    response.writeHead(200, { 'Content-Type': 'text/event-stream' })
    const frame = (delta, reason) => ({ id: 'response_' + requests.length, object: 'chat.completion.chunk', model: 'test-model',
      choices: [{ index: 0, delta, finish_reason: reason }] })
    response.write('data: ' + JSON.stringify(frame(delta, null)) + '\n\n')
    response.write('data: ' + JSON.stringify(frame({}, typeof output === 'string' ? 'stop' : 'tool_calls')) + '\n\n')
    response.write('data: ' + JSON.stringify({ choices: [], usage: { prompt_tokens: 10, completion_tokens: 3, total_tokens: 13 } }) + '\n\n')
    response.end('data: [DONE]\n\n')
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const folder = mkdtempSync(join(tmpdir(), 'ruoyi-pi-test-'))
  const child = spawn(process.execPath, ['--max-old-space-size=192', workerPath], { cwd: folder,
    env: { PATH: resolve(process.execPath, '..'), SystemRoot: process.env.SystemRoot || '', LANG: 'C.UTF-8' },
    windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  let diagnostic = ''
  child.stderr.on('data', data => { diagnostic = (diagnostic + data).slice(-2048) })
  const frames = [], waiting = []
  createInterface({ input: child.stdout }).on('line', line => {
    const value = JSON.parse(line)
    if (waiting.length) waiting.shift()(value)
    else frames.push(value)
  })
  const exited = new Promise(resolve => child.on('exit', (code, signal) => resolve({ code, signal })))
  return { requests, folder, child, exited,
    start(request) { child.stdin.write(JSON.stringify({ type: 'start', request, runtime: {
      baseUrl: `http://127.0.0.1:${server.address().port}/v1`, apiKey: 'fixture-credential-only',
      model: 'test-model', contextWindow: 65536, outputLimit: 1024, timeoutMs: 5000
    } }) + '\n') },
    send(packet) { child.stdin.write(JSON.stringify(packet) + '\n') },
    async next() {
      let timer
      try { return await Promise.race([frames.length ? Promise.resolve(frames.shift()) : new Promise(resolve => waiting.push(resolve)),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('框架无回执：' + diagnostic)), 30000) })]) }
      finally { clearTimeout(timer) }
    },
    async close() {
      child.stdin.end()
      await Promise.race([exited, pause(2000).then(() => child.kill())])
      await exited
      server.closeAllConnections()
      await new Promise(resolve => server.close(resolve))
      assert.ok(resolve(folder).startsWith(resolve(tmpdir()) + '\\') || resolve(folder).startsWith(resolve(tmpdir()) + '/'))
      rmSync(folder, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
    }
  }
}
const initial = () => ({ modelId: 1, reasoningEffort: null, promptCacheKey: 'ruoyi:test',
  messages: [{ role: 'SYSTEM', content: '只用获批工具' }, { role: 'USER', content: '完成受控任务' }], tools: [tool('discover')] })
function receipt(request, event, content) {
  request.messages.push({ role: 'ASSISTANT', content: event.text, toolCalls: [
    { id: 'rtc_' + event.bridgeId, name: event.name, arguments: event.arguments }
  ] }, { role: 'TOOL', toolCallId: 'rtc_' + event.bridgeId, toolName: event.name, content })
  return { type: 'resume', bridgeId: event.bridgeId, request }
}

test('实际 Pi 循环等待权威回执，动态披露并保留参数失败供模型修正', async () => {
  const fx = await fixture((body, number) => number === 1 ? { name: 'discover' }
    : number === 2 ? { name: 'authorized_query', args: { limit: 100 } }
      : number === 3 ? { name: 'authorized_query', args: { limit: 1 } } : '真实回执已完成')
  try {
    const request = initial()
    fx.start(request)
    let event = await fx.next()
    assert.equal(event.type, 'tool')
    await pause(200)
    assert.equal(fx.requests.length, 1)
    assert.deepEqual(fx.requests[0].tools.map(item => item.function.name), ['discover'])
    request.tools.push(tool('authorized_query'))
    fx.send(receipt(request, event, '{"loaded":"authorized_query"}'))
    event = await fx.next()
    assert.equal(event.name, 'authorized_query')
    assert.equal(JSON.parse(event.arguments).limit, 100)
    assert.equal(fx.requests[1].tools[1].function.parameters.properties.limit.maximum, 1)
    fx.send(receipt(request, event, '{"success":false,"error":"limit 超过 1"}'))
    event = await fx.next()
    assert.equal(JSON.parse(event.arguments).limit, 1)
    fx.send(receipt(request, event, '{"success":true,"rows":[1]}'))
    event = await fx.next()
    assert.equal(event.type, 'result')
    assert.equal(event.turns, 4)
    assert.equal(event.text, '真实回执已完成')
    assert.equal(fx.requests.length, 4)
    assert.ok(fx.requests[2].messages.at(-1).content.includes('limit 超过 1'))
    for (const file of readdirSync(fx.folder, { recursive: true, withFileTypes: true }).filter(file => file.isFile())) {
      assert.ok(!readFileSync(join(file.parentPath, file.name), 'utf8').includes('fixture-credential-only'))
    }
  } finally { await fx.close() }
})

for (const mode of ['cancel', 'eof', 'wrong-receipt']) {
  test(`等待期间 ${mode} 不继续调用模型`, async () => {
    const fx = await fixture(() => ({ name: 'discover' }))
    try {
      fx.start(initial())
      assert.equal((await fx.next()).type, 'tool')
      if (mode === 'cancel') fx.send({ type: 'cancel' })
      else if (mode === 'eof') fx.child.stdin.end()
      else fx.send({ type: 'resume', bridgeId: 'forged', request: initial() })
      await Promise.race([fx.exited, pause(3000).then(() => { throw new Error('子进程未退出') })])
      assert.equal(fx.requests.length, 1)
    } finally { await fx.close() }
  })
}

test('明确不支持缓存参数时只做一次兼容请求，继续由 Pi 调用模型', async () => {
  const fx = await fixture(body => body.prompt_cache_key ? { status: 400, error: 'Unsupported parameter: prompt_cache_key' } : '兼容完成')
  try {
    const request = initial()
    request.tools = []
    fx.start(request)
    const result = await fx.next()
    assert.equal(result.type, 'result')
    assert.equal(result.text, '兼容完成')
    assert.equal(fx.requests.length, 2)
    assert.equal(fx.requests[1].prompt_cache_key, undefined)
  } finally { await fx.close() }
})

test('未声明的宿主工具在执行前拒绝，不进入下一轮', async () => {
  const fx = await fixture(() => ({ name: 'host_bash', args: { command: '不可执行' } }))
  try {
    fx.start(initial())
    const result = await fx.next()
    assert.equal(result.type, 'error')
    assert.equal(result.code, 'UNAPPROVED_TOOL')
    assert.equal(result.toolName, 'host_bash')
    assert.equal(fx.requests.length, 1)
  } finally { await fx.close() }
})
