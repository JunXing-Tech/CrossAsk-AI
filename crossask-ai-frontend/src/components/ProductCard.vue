<script setup>
const props = defineProps({
  product: { type: Object, required: true }
})

function openLink() {
  if (props.product.sourceUrl) {
    window.open(props.product.sourceUrl, '_blank', 'noopener')
  }
}
</script>

<template>
  <div class="card" @click="openLink">
    <div class="title">{{ product.title }}</div>

    <div class="price-row">
      <span class="price">${{ product.price }}</span>
      <span v-if="product.freeShipping" class="badge badge-free">免邮</span>
      <span v-else-if="product.shippingText" class="badge badge-ship">{{ product.shippingText }}</span>
    </div>

    <div class="tags">
      <span v-if="product.brand" class="tag">{{ product.brand }}</span>
      <span v-if="product.conditionText" class="tag">{{ product.conditionText }}</span>
    </div>

    <div class="footer">
      <span class="seller" :title="product.sellerName">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
        </svg>
        {{ product.sellerName }}
      </span>
      <span class="link">在 eBay 查看 →</span>
    </div>
  </div>
</template>

<style scoped>
.card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 14px;
  cursor: pointer;
  transition: all 0.18s;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.card:hover {
  border-color: var(--accent);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.card:hover .link {
  opacity: 1;
}
.title {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.45;
  color: var(--text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 37px;
}
.price-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.price {
  font-size: 20px;
  font-weight: 700;
  color: var(--accent-hover);
  letter-spacing: -0.5px;
}
.badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;
}
.badge-free {
  background: var(--success);
  color: #fff;
}
.badge-ship {
  background: var(--bg-sidebar);
  color: var(--text-secondary);
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  font-size: 11.5px;
  color: var(--text-secondary);
  background: var(--bg-app);
  border: 1px solid var(--border);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}
.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 2px;
  padding-top: 9px;
  border-top: 1px solid var(--border);
}
.seller {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.seller svg {
  flex-shrink: 0;
}
.link {
  font-size: 11.5px;
  color: var(--accent);
  white-space: nowrap;
  opacity: 0.7;
  transition: opacity 0.15s;
  flex-shrink: 0;
}
</style>
