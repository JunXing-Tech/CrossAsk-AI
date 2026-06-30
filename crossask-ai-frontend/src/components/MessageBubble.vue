<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'
import ProductCard from './ProductCard.vue'
import SourceList from './SourceList.vue'

const props = defineProps({
  message: { type: Object, required: true }
})

const isUser = computed(() => props.message.role === 'user')
const renderedContent = computed(() =>
  isUser.value ? null : renderMarkdown(props.message.content)
)
</script>

<template>
  <div class="row" :class="isUser ? 'row-user' : 'row-assistant'">
    <!-- 助手头像 -->
    <div v-if="!isUser" class="avatar">✦</div>

    <div class="bubble" :class="isUser ? 'bubble-user' : 'bubble-assistant'">
      <!-- 用户：纯文本 -->
      <div v-if="isUser" class="user-text">{{ message.content }}</div>

      <!-- 助手 -->
      <template v-else>
        <div v-if="message.content" class="markdown-body" v-html="renderedContent" />
        <div v-if="!message.content && message.streaming" class="thinking">
          <span class="dot" /><span class="dot" /><span class="dot" />
        </div>

        <div v-if="message.error" class="error">⚠️ {{ message.error }}</div>

        <div v-if="message.products && message.products.length" class="product-grid">
          <ProductCard
            v-for="(p, i) in message.products"
            :key="i"
            :product="p"
          />
        </div>

        <SourceList :sources="message.sources || []" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  gap: 12px;
  margin: 20px 0;
}
.row-user {
  justify-content: flex-end;
}
.row-assistant {
  justify-content: flex-start;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  flex-shrink: 0;
  margin-top: 2px;
}
.bubble {
  max-width: 82%;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  font-size: 14.5px;
  line-height: 1.65;
}
.bubble-user {
  background: var(--bg-user-bubble);
  color: var(--text-primary);
  border-bottom-right-radius: 4px;
}
.bubble-assistant {
  background: transparent;
  color: var(--text-primary);
  padding-left: 0;
  padding-top: 4px;
}
.user-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 6px 0;
}
.markdown-body :deep(p:first-child) {
  margin-top: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}
.markdown-body :deep(li) {
  margin: 3px 0;
}
.markdown-body :deep(code) {
  background: var(--bg-sidebar);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
  font-family: "SF Mono", Consolas, monospace;
}
.markdown-body :deep(pre) {
  background: var(--bg-sidebar);
  padding: 12px;
  border-radius: var(--radius-sm);
  overflow-x: auto;
}
.markdown-body :deep(a) {
  color: var(--accent-hover);
  text-decoration: none;
}
.markdown-body :deep(a:hover) {
  text-decoration: underline;
}
.markdown-body :deep(strong) {
  font-weight: 600;
}

.thinking {
  display: flex;
  gap: 5px;
  padding: 6px 0;
}
.dot {
  width: 7px;
  height: 7px;
  background: var(--accent);
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.error {
  margin-top: 8px;
  padding: 10px 14px;
  background: var(--error-bg);
  border: 1px solid #f0c9c3;
  border-radius: var(--radius-sm);
  color: var(--error);
  font-size: 13px;
}

.product-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 12px;
}
</style>
