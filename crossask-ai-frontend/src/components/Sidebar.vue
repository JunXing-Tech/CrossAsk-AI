<script setup>
import { computed } from 'vue'
import { useChatStore } from '../stores/chat.js'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  width: { type: Number, default: 280 },
  resizing: { type: Boolean, default: false }
})
const emit = defineEmits(['toggle', 'resize-start'])

const store = useChatStore()

// 按时间分组：今天 / 昨天 / 更早
const grouped = computed(() => {
  const groups = { today: [], yesterday: [], earlier: [] }
  const now = new Date()
  const todayStr = now.toDateString()
  const yesterday = new Date(now.getTime() - 86400000).toDateString()

  for (const s of store.sessions) {
    const d = s.lastTime ? new Date(s.lastTime.replace(' ', 'T')) : null
    const ds = d ? d.toDateString() : ''
    if (ds === todayStr) groups.today.push(s)
    else if (ds === yesterday) groups.yesterday.push(s)
    else groups.earlier.push(s)
  }
  return groups
})

function selectSession(sessionId) {
  store.switchSession(sessionId)
}

function newChat() {
  store.startNewSession()
}

function onDelete(e, sessionId) {
  e.stopPropagation()
  store.removeSession(sessionId)
}
</script>

<template>
  <aside
    class="sidebar"
    :class="{ collapsed, resizing }"
    :style="collapsed ? {} : { width: width + 'px' }"
  >
    <div class="sidebar-header">
      <div class="logo-row" v-if="!collapsed">
        <span class="logo-mark">✦</span>
        <span class="logo-text">CrossAsk</span>
      </div>
      <span v-else class="logo-mark logo-mark-collapsed">✦</span>
      <button class="icon-btn toggle-btn" @click="emit('toggle')" :title="collapsed ? '展开' : '收起'">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 12h18M3 6h18M3 18h18" />
        </svg>
      </button>
    </div>

    <button
      class="new-chat-btn"
      @click="newChat"
      :class="{ 'icon-only': collapsed }"
      :disabled="store.isBlankSession && !store.isStreaming"
      :title="store.isBlankSession ? '当前已是新对话' : '开始新对话'"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M12 5v14M5 12h14" />
      </svg>
      <span v-if="!collapsed">新对话</span>
    </button>

    <div class="session-list" v-if="!collapsed">
      <template v-for="(items, group) in grouped" :key="group">
        <div v-if="items.length" class="group">
          <div class="group-label">
            {{ group === 'today' ? '今天' : group === 'yesterday' ? '昨天' : '更早' }}
          </div>
          <div
            v-for="s in items"
            :key="s.sessionId"
            class="session-item"
            :class="{ active: s.sessionId === store.sessionId }"
            @click="selectSession(s.sessionId)"
          >
            <span class="session-title">{{ s.title }}</span>
            <button class="del-btn" @click="onDelete($event, s.sessionId)" title="删除">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
              </svg>
            </button>
          </div>
        </div>
      </template>

      <div v-if="store.sessions.length === 0" class="empty-hint">
        还没有历史对话
      </div>
    </div>

    <div class="sidebar-footer" v-if="!collapsed">
      <span class="footer-text">跨境电商客服助手</span>
    </div>

    <!-- 拖拽调宽手柄（仅展开态） -->
    <div
      v-if="!collapsed"
      class="resize-handle"
      @mousedown="emit('resize-start', $event)"
      title="拖拽调整宽度"
    />
  </aside>
</template>

<style scoped>
.sidebar {
  width: 280px;
  height: 100%;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  flex-shrink: 0;
  position: relative;
}
.sidebar.resizing {
  transition: none;
  user-select: none;
}
.sidebar.collapsed {
  width: 64px;
}

/* 拖拽手柄：贴右边缘的窄条，hover/拖拽时高亮 */
.resize-handle {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 10;
}
.resize-handle::after {
  content: '';
  position: absolute;
  top: 0;
  left: 2px;
  width: 2px;
  height: 100%;
  background: transparent;
  transition: background 0.15s;
}
.resize-handle:hover::after,
.sidebar.resizing .resize-handle::after {
  background: var(--accent);
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 14px;
}
.sidebar.collapsed .sidebar-header {
  flex-direction: column;
  gap: 12px;
  padding: 16px 0;
}
.logo-mark-collapsed {
  font-size: 22px;
}
.logo-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.logo-mark {
  color: var(--accent);
  font-size: 20px;
}
.logo-text {
  font-weight: 700;
  font-size: 17px;
  letter-spacing: 0.3px;
}
.icon-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 6px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}
.icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.new-chat-btn {
  margin: 0 14px 12px;
  padding: 10px 14px;
  background: var(--accent);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  transition: background 0.15s;
  font-family: inherit;
}
.new-chat-btn:hover {
  background: var(--accent-hover);
}
.new-chat-btn:disabled {
  background: var(--border-strong);
  color: var(--text-tertiary);
  cursor: default;
}
.new-chat-btn.icon-only {
  margin: 0 auto 12px;
  width: 40px;
  padding: 10px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}
.group {
  margin-bottom: 12px;
}
.group-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 6px 10px;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.15s;
  gap: 6px;
}
.session-item:hover {
  background: var(--bg-hover);
}
.session-item.active {
  background: var(--accent-soft);
}
.session-title {
  font-size: 13.5px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}
.session-item.active .session-title {
  color: var(--accent-hover);
  font-weight: 500;
}
.del-btn {
  background: transparent;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  display: none;
  align-items: center;
}
.session-item:hover .del-btn {
  display: flex;
}
.del-btn:hover {
  color: var(--error);
  background: var(--error-bg);
}

.empty-hint {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
  padding: 24px 0;
}

.sidebar-footer {
  padding: 14px;
  border-top: 1px solid var(--border);
}
.footer-text {
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
