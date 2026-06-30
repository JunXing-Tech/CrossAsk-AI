import { defineStore } from 'pinia'
import {
  streamAsk,
  fetchSessions,
  fetchSessionMessages,
  deleteSession as apiDeleteSession
} from '../api/client.js'
import {
  getOrCreateSessionId,
  newSession,
  setCurrentSessionId
} from '../utils/session.js'

/**
 * v1.1 聊天状态管理（多会话）。
 * - sessions: 侧边栏会话列表 [{ sessionId, title, lastTime, msgCount }]
 * - messages: 当前会话消息 [{ role, content, sources, products, error, streaming }]
 * - sessionId: 当前活跃会话 ID（与 localStorage 同步）
 *
 * 会话生命周期：
 *  - "新对话" 只清空 messages 并生成新 sessionId，不写库；
 *  - 首次发消息后后端 chat_history 才有记录，loadSessions 才会出现该会话条目；
 *  - 切换历史会话 = 拉取其消息 + 同步 sessionId 到 localStorage。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: getOrCreateSessionId(),
    sessions: [],
    messages: [],
    isStreaming: false,
    currentController: null,
    loadingHistory: false
  }),

  getters: {
    /** 当前是否是「空白新会话」（无消息、且不在已保存列表里）。 */
    isBlankSession(state) {
      return state.messages.length === 0
    }
  },

  actions: {
    /** 启动初始化：加载会话列表，并恢复当前会话消息（若它有历史）。 */
    async init() {
      await this.loadSessions()
      // 若 localStorage 里的 sessionId 在已保存列表中，说明刷新前有对话，恢复它
      const exists = this.sessions.some((s) => s.sessionId === this.sessionId)
      if (exists) {
        await this.loadCurrentMessages()
      }
    },

    /** 加载侧边栏会话列表。 */
    async loadSessions() {
      try {
        this.sessions = await fetchSessions(50)
      } catch (e) {
        console.warn('加载会话列表失败', e)
      }
    },

    /** 加载当前 sessionId 的消息到对话区。 */
    async loadCurrentMessages() {
      this.loadingHistory = true
      try {
        const msgs = await fetchSessionMessages(this.sessionId)
        this.messages = msgs.map(toMessage)
      } catch (e) {
        console.warn('加载会话消息失败', e)
      } finally {
        this.loadingHistory = false
      }
    },

    /** 切换到某个历史会话，拉取其消息还原对话。 */
    async switchSession(sessionId) {
      if (this.isStreaming || sessionId === this.sessionId) return
      this.loadingHistory = true
      try {
        const msgs = await fetchSessionMessages(sessionId)
        this.sessionId = sessionId
        setCurrentSessionId(sessionId) // 同步 localStorage，修复刷新回退
        this.messages = msgs.map(toMessage)
      } catch (e) {
        console.warn('加载会话消息失败', e)
      } finally {
        this.loadingHistory = false
      }
    },

    /**
     * 新建会话。若当前已经是空白新会话则不重复创建（避免产生一堆废弃 sessionId）。
     */
    startNewSession() {
      if (this.currentController) {
        this.currentController.abort()
        this.currentController = null
      }
      this.isStreaming = false
      // 当前已是空白会话：无需新建，直接复用
      if (this.messages.length === 0) {
        return
      }
      this.sessionId = newSession()
      this.messages = []
    },

    /** 删除某会话；若删的是当前会话则回到空白新会话。 */
    async removeSession(sessionId) {
      try {
        await apiDeleteSession(sessionId)
      } catch (e) {
        console.warn('删除会话失败', e)
      }
      this.sessions = this.sessions.filter((s) => s.sessionId !== sessionId)
      if (sessionId === this.sessionId) {
        // 删的是当前会话：清空并切到新会话
        this.sessionId = newSession()
        this.messages = []
        this.isStreaming = false
      }
    },

    async sendQuestion(question) {
      if (!question || !question.trim() || this.isStreaming) return

      // 1. 用户气泡
      this.messages.push({ role: 'user', content: question.trim() })

      // 2. 占位助手气泡（通过索引取 reactive 引用）
      const idx =
        this.messages.push({
          role: 'assistant',
          content: '',
          sources: [],
          products: [],
          error: null,
          streaming: true
        }) - 1
      const assistantMsg = this.messages[idx]
      this.isStreaming = true

      // 3. 流式请求
      streamAsk(question.trim(), this.sessionId, {
        onToken: (t) => {
          assistantMsg.content += t
        },
        onMetadata: (m) => {
          assistantMsg.sources = m.sources || []
          assistantMsg.products = m.products || []
        },
        onDone: () => {
          assistantMsg.streaming = false
          this.isStreaming = false
          this.currentController = null
          // 回复完成后刷新会话列表（首条消息会创建新会话条目）
          this.loadSessions()
        },
        onError: (errMsg) => {
          assistantMsg.streaming = false
          assistantMsg.error = mapErrorMessage(errMsg)
          this.isStreaming = false
          this.currentController = null
        }
      }).then((controller) => {
        this.currentController = controller
      })
    }
  }
})

function toMessage(m) {
  return {
    role: m.role,
    content: m.content,
    sources: [],
    products: [],
    error: null,
    streaming: false
  }
}

function mapErrorMessage(raw) {
  if (!raw) return '服务暂时不可用，请稍后重试'
  if (raw.includes('400')) return '请输入问题内容'
  if (raw.includes('5')) return '服务暂时不可用，请稍后重试'
  if (raw.toLowerCase().includes('network') || raw.toLowerCase().includes('fetch')) {
    return '网络异常，请检查连接'
  }
  return `回答中断：${raw}`
}
