/**
 * sessionId 管理：用 localStorage 持久化"当前活跃会话 ID"，刷新页面后保持。
 * clientId 管理：浏览器级唯一标识，用于后端会话隔离，不同浏览器互不可见。
 */
const KEY = 'crossask_session_id'
const CLIENT_KEY = 'crossask_client_id'

/** 读取或生成浏览器级 clientId（首次访问生成，之后持久化）。 */
export function getOrCreateClientId() {
  let id = localStorage.getItem(CLIENT_KEY)
  if (!id) {
    id = generateUuid()
    localStorage.setItem(CLIENT_KEY, id)
  }
  return id
}

/** 读取当前会话 ID；没有则生成一个新的并持久化。 */
export function getOrCreateSessionId() {
  let id = localStorage.getItem(KEY)
  if (!id) {
    id = generateUuid()
    localStorage.setItem(KEY, id)
  }
  return id
}

/** 生成新会话 ID 并设为当前活跃会话。 */
export function newSession() {
  const id = generateUuid()
  localStorage.setItem(KEY, id)
  return id
}

/** 把某个已存在的会话设为当前活跃会话（切换历史会话时用）。 */
export function setCurrentSessionId(id) {
  if (id) localStorage.setItem(KEY, id)
}

export function generateUuid() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  // fallback
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
