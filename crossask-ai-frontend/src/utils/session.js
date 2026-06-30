/**
 * sessionId 管理：用 localStorage 持久化"当前活跃会话 ID"，刷新页面后保持。
 */
const KEY = 'crossask_session_id'

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
