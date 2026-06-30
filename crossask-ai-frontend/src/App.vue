<script setup>
import { nextTick, watch, ref, onMounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import MessageBubble from './components/MessageBubble.vue'
import ChatInput from './components/ChatInput.vue'
import { useChatStore } from './stores/chat.js'

const store = useChatStore()
const scrollContainer = ref(null)
const sidebarCollapsed = ref(false)

// 侧边栏宽度（可拖拽，记忆到 localStorage）
const SIDEBAR_MIN = 220
const SIDEBAR_MAX = 420
const SIDEBAR_KEY = 'crossask_sidebar_width'
const sidebarWidth = ref(loadSidebarWidth())
const resizing = ref(false)

function loadSidebarWidth() {
  const saved = parseInt(localStorage.getItem(SIDEBAR_KEY), 10)
  if (!isNaN(saved) && saved >= SIDEBAR_MIN && saved <= SIDEBAR_MAX) return saved
  return 280
}

function clamp(v) {
  return Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, v))
}

function onResizeStart(e) {
  resizing.value = true
  const startX = e.clientX
  const startW = sidebarWidth.value

  function onMove(ev) {
    sidebarWidth.value = clamp(startW + (ev.clientX - startX))
  }
  function onUp() {
    resizing.value = false
    localStorage.setItem(SIDEBAR_KEY, String(sidebarWidth.value))
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.cursor = ''
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
  document.body.style.cursor = 'col-resize'
}

const capabilities = [
  {
    icon: '🛍️',
    title: '商品查询',
    desc: '实时查询在售商品的价格、成色、卖家与运费',
    examples: ['iPhone 15 现在多少钱？', '有没有 200 美元以内的耳机？']
  },
  {
    icon: '📦',
    title: '政策与物流',
    desc: '解读 eBay 与 USPS 的退货、运费、关税与时效规则',
    examples: ['eBay 上的商品怎么申请退货？', 'USPS 国际包裹寄到中国要几天？']
  },
  {
    icon: '🔗',
    title: '综合咨询',
    desc: '把商品信息与平台政策结合，给出完整购物建议',
    examples: ['想退掉买的 iPhone 15，该怎么操作？']
  }
]

function ask(q) {
  if (store.isStreaming) return
  store.sendQuestion(q)
}

onMounted(() => {
  store.init()
})

watch(
  () => store.messages.map((m) => m.content).join('') + store.messages.length,
  async () => {
    await nextTick()
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  }
)
</script>

<template>
  <div class="layout">
    <Sidebar
      :collapsed="sidebarCollapsed"
      :width="sidebarWidth"
      :resizing="resizing"
      @toggle="sidebarCollapsed = !sidebarCollapsed"
      @resize-start="onResizeStart"
    />

    <div class="main">
      <main class="messages" ref="scrollContainer">
        <!-- 欢迎页 -->
        <div v-if="store.messages.length === 0" class="welcome">
          <div class="welcome-inner">
            <div class="hero-mark">✦</div>
            <h1>你好，我是 CrossAsk</h1>
            <p class="hero-sub">
              你的跨境电商智能客服，可查询 <strong>eBay 在售商品</strong>，也能解读
              <strong>eBay / USPS 政策物流</strong>。<br />
              选一个下面的问题开始，或直接在底部输入你的问题。
            </p>

            <div class="cap-grid">
              <div v-for="cap in capabilities" :key="cap.title" class="cap-card">
                <div class="cap-icon">{{ cap.icon }}</div>
                <div class="cap-title">{{ cap.title }}</div>
                <div class="cap-desc">{{ cap.desc }}</div>
                <div class="cap-examples">
                  <button
                    v-for="ex in cap.examples"
                    :key="ex"
                    class="example-chip"
                    :disabled="store.isStreaming"
                    @click="ask(ex)"
                  >
                    {{ ex }}
                  </button>
                </div>
              </div>
            </div>

            <div class="scope-note">
              <strong>关于回答范围：</strong>我的知识来自已收录的 eBay 商品库与 eBay / USPS 官方帮助文档。
              超出范围的问题（如天气、汇率、其他购物平台）我会如实告知，不会编造答案。
            </div>
          </div>
        </div>

        <!-- 对话区 -->
        <div v-else class="conversation">
          <div v-if="store.loadingHistory" class="loading-history">加载历史对话…</div>
          <MessageBubble
            v-for="(msg, i) in store.messages"
            :key="i"
            :message="msg"
          />
        </div>
      </main>

      <ChatInput />
    </div>
  </div>
</template>

<style scoped>
.layout {
  height: 100vh;
  display: flex;
  overflow: hidden;
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-app);
}

.messages {
  flex: 1;
  overflow-y: auto;
  scroll-behavior: smooth;
}

/* 欢迎页 */
.welcome {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}
.welcome-inner {
  max-width: 720px;
  width: 100%;
  text-align: center;
}
.hero-mark {
  font-size: 40px;
  color: var(--accent);
  margin-bottom: 12px;
}
.welcome-inner h1 {
  font-size: 32px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 12px;
  letter-spacing: -0.5px;
}
.hero-sub {
  font-size: 15px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 0 0 36px;
}
.hero-sub strong {
  color: var(--accent-hover);
  font-weight: 600;
}

.cap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 28px;
}
.cap-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 18px;
  text-align: left;
  transition: all 0.2s;
}
.cap-card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.cap-icon {
  font-size: 26px;
  margin-bottom: 8px;
}
.cap-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}
.cap-desc {
  font-size: 12.5px;
  color: var(--text-tertiary);
  margin-bottom: 12px;
  line-height: 1.5;
}
.cap-examples {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.example-chip {
  text-align: left;
  font-size: 13px;
  color: var(--text-secondary);
  background: var(--bg-app);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 11px;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
  line-height: 1.4;
}
.example-chip:hover:not(:disabled) {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent-hover);
}
.example-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.scope-note {
  font-size: 12.5px;
  color: var(--text-tertiary);
  line-height: 1.7;
  background: var(--bg-sidebar);
  border-radius: var(--radius-md);
  padding: 14px 18px;
  text-align: left;
}
.scope-note strong {
  color: var(--text-secondary);
  font-weight: 600;
}

/* 对话区 */
.conversation {
  max-width: 760px;
  margin: 0 auto;
  padding: 28px 24px 40px;
}
.loading-history {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
  padding: 12px;
}

@media (max-width: 720px) {
  .cap-grid {
    grid-template-columns: 1fr;
  }
  .welcome-inner h1 {
    font-size: 26px;
  }
}
</style>
