<script setup>
import { ref } from 'vue'

defineProps({
  sources: { type: Array, required: true }
})
const open = ref(false)
</script>

<template>
  <div v-if="sources && sources.length" class="source-list">
    <button class="toggle" @click="open = !open">
      <svg class="chevron" :class="{ open }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M9 18l6-6-6-6" />
      </svg>
      <span>参考来源 · {{ sources.length }}</span>
    </button>
    <transition name="expand">
      <ul v-show="open">
        <li v-for="(s, i) in sources" :key="i">
          <a :href="s.sourceUrl" target="_blank" rel="noopener">
            <span class="dot">·</span>{{ s.sourceTitle || s.sourceUrl }}
          </a>
        </li>
      </ul>
    </transition>
  </div>
</template>

<style scoped>
.source-list {
  margin-top: 12px;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  border: none;
  color: var(--text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  padding: 4px 0;
  font-family: inherit;
}
.toggle:hover {
  color: var(--accent-hover);
}
.chevron {
  transition: transform 0.2s;
}
.chevron.open {
  transform: rotate(90deg);
}
ul {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}
li {
  padding: 3px 0;
}
a {
  font-size: 12.5px;
  color: var(--text-secondary);
  text-decoration: none;
  display: flex;
  gap: 6px;
  line-height: 1.5;
}
a:hover {
  color: var(--accent-hover);
}
.dot {
  color: var(--accent);
}
.expand-enter-active,
.expand-leave-active {
  transition: all 0.2s ease;
  overflow: hidden;
}
.expand-enter-from,
.expand-leave-to {
  opacity: 0;
  max-height: 0;
}
.expand-enter-to,
.expand-leave-from {
  opacity: 1;
  max-height: 400px;
}
</style>
