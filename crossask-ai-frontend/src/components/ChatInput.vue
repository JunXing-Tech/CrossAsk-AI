<script setup>
import { ref } from 'vue'
import { useChatStore } from '../stores/chat.js'

const store = useChatStore()
const input = ref('')
const textareaRef = ref(null)

function send() {
  if (!input.value.trim() || store.isStreaming) return
  const q = input.value
  input.value = ''
  autoResize()
  store.sendQuestion(q)
}

function onEnter(e) {
  if (!e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}
</script>

<template>
  <div class="input-wrap">
    <div class="input-box">
      <textarea
        ref="textareaRef"
        v-model="input"
        rows="1"
        placeholder="给 CrossAsk 发送消息…"
        :disabled="store.isStreaming"
        @keydown.enter="onEnter"
        @input="autoResize"
      />
      <button
        class="send-btn"
        :disabled="!input.trim() || store.isStreaming"
        @click="send"
        title="发送"
      >
        <svg v-if="!store.isStreaming" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
        <span v-else class="spinner" />
      </button>
    </div>
    <div class="input-hint">CrossAsk 可能出错，重要信息请以官方为准 · Enter 发送，Shift+Enter 换行</div>
  </div>
</template>

<style scoped>
.input-wrap {
  padding: 12px 24px 20px;
  background: var(--bg-app);
}
.input-box {
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  padding: 10px 10px 10px 16px;
  box-shadow: var(--shadow-sm);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-box:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-family: inherit;
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--text-primary);
  background: transparent;
  max-height: 200px;
  padding: 4px 0;
}
textarea::placeholder {
  color: var(--text-tertiary);
}
.send-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  border: none;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background 0.15s;
}
.send-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}
.send-btn:disabled {
  background: var(--border-strong);
  cursor: not-allowed;
}
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.input-hint {
  max-width: 760px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 11.5px;
  color: var(--text-tertiary);
}
</style>
