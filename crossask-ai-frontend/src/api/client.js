/**
 * v1.1 SSE 客户端：用 fetch + ReadableStream 接收 /ask/stream 推送的事件。
 * EventSource 只支持 GET，POST 必须自己解析 SSE 帧。
 * v1.2 所有请求携带 X-Client-Id 请求头，实现浏览器级会话隔离。
 *
 * 事件类型：
 * - token: 一段 token 文本（含转义的 \n）
 * - metadata: JSON { sources, products }
 * - done: 流结束
 * - error: 错误信息
 */
import { getOrCreateClientId } from '../utils/session.js'

const clientId = getOrCreateClientId()

export async function streamAsk(question, sessionId, callbacks) {
  const controller = new AbortController()

  try {
    const resp = await fetch('/api/ask/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'X-Client-Id': clientId
      },
      body: JSON.stringify({ question, sessionId }),
      signal: controller.signal
    })

    if (!resp.ok) {
      callbacks.onError?.(`HTTP ${resp.status}`)
      return controller
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 按 \n\n 分帧
      const frames = buffer.split('\n\n')
      buffer = frames.pop() // 最后一个可能不完整，保留
      for (const frame of frames) {
        handleFrame(frame, callbacks)
      }
    }
    // 处理 buffer 中可能残留的最后一帧
    if (buffer.trim()) handleFrame(buffer, callbacks)
  } catch (e) {
    if (e.name !== 'AbortError') {
      callbacks.onError?.(e.message || String(e))
    }
  }

  return controller
}

function handleFrame(frame, callbacks) {
  const lines = frame.split('\n')
  let event = ''
  // SSE data 可能有多行 data:
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.substring(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.substring(5).trimStart())
    }
  }
  const data = dataLines.join('\n')
  if (!event) return

  if (event === 'token') {
    callbacks.onToken?.(unescapeFromSse(data))
  } else if (event === 'metadata') {
    try {
      callbacks.onMetadata?.(JSON.parse(data))
    } catch (e) {
      console.warn('metadata 解析失败', e, data)
    }
  } else if (event === 'done') {
    callbacks.onDone?.()
  } else if (event === 'error') {
    callbacks.onError?.(unescapeFromSse(data))
  }
}

/** 与后端 escapeForSse 对应：\\n -> \n, \\r -> \r, \\\\ -> \\ */
function unescapeFromSse(s) {
  if (!s) return ''
  return s.replace(/\\n/g, '\n').replace(/\\r/g, '\r').replace(/\\\\/g, '\\')
}

/* ============ v1.1 会话历史接口 ============ */

/** 拉取会话列表（最近活跃倒序，按 clientId 隔离）。 */
export async function fetchSessions(limit = 50) {
  const resp = await fetch(`/api/sessions?limit=${limit}`, {
    headers: { 'X-Client-Id': clientId }
  })
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  return resp.json()
}

/** 拉取某会话全部消息（正序，需 clientId 校验）。 */
export async function fetchSessionMessages(sessionId) {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
    headers: { 'X-Client-Id': clientId }
  })
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  return resp.json()
}

/** 删除某会话（需 clientId 校验防止越权）。 */
export async function deleteSession(sessionId) {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: { 'X-Client-Id': clientId }
  })
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  return resp.json()
}
