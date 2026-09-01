<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore, type DecryptedVaultItem } from '../stores/vault'

const route = useRoute()
const router = useRouter()
const vault = useVaultStore()

const itemId = computed(() => Number(route.params.id))
const item = ref<DecryptedVaultItem | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const revealed = ref<Set<number>>(new Set())
const deleting = ref(false)
const showDeleteConfirm = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    item.value = await vault.fetchItem(itemId.value)
    if (!vault.categories.length) {
      await vault.fetchCategories()
    }
  } catch {
    loadError.value = vault.errorMessage ?? 'Failed to load this item.'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function toggleReveal(index: number): void {
  const next = new Set(revealed.value)
  if (next.has(index)) {
    next.delete(index)
  } else {
    next.add(index)
  }
  revealed.value = next
}

function categoryName(categoryId: number): string {
  return vault.categories.find((c) => c.id === categoryId)?.name ?? 'Unknown'
}

async function confirmDelete(): Promise<void> {
  deleting.value = true
  try {
    await vault.deleteItem(itemId.value)
    await router.push('/dashboard')
  } catch {
    // vault.errorMessage already holds a user-facing message; stay on page.
  } finally {
    deleting.value = false
    showDeleteConfirm.value = false
  }
}
</script>

<template>
  <main class="item-page">
    <RouterLink to="/dashboard" class="back-link">&larr; Home</RouterLink>

    <div v-if="loading">Loading…</div>
    <div v-else-if="loadError || !item" class="banner banner-error" role="alert">
      {{ loadError ?? "This item doesn't exist, or you don't have access to it." }}
    </div>
    <template v-else>
      <header>
        <div>
          <h1>{{ item.payload.title }}</h1>
          <p class="category-tag">{{ categoryName(item.categoryId) }}</p>
        </div>
        <div class="actions">
          <RouterLink :to="`/items/${item.id}/edit`" class="button-link">Edit</RouterLink>
          <button type="button" class="danger" @click="showDeleteConfirm = true">Delete</button>
        </div>
      </header>

      <div v-if="showDeleteConfirm" class="confirm-box">
        <p>Delete this item permanently? This cannot be undone.</p>
        <div class="confirm-actions">
          <button type="button" class="danger" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? 'Deleting…' : 'Confirm delete' }}
          </button>
          <button type="button" @click="showDeleteConfirm = false">Cancel</button>
        </div>
      </div>

      <ul class="blocks">
        <li v-for="(block, index) in item.payload.content" :key="index" class="block">
          <div class="block-header">
            <span class="block-type">{{ block.type }}</span>
            <span v-if="block.label" class="block-label">{{ block.label }}</span>
          </div>
          <div class="block-value">
            <span v-if="block.type === 'secret' && !revealed.has(index)" class="masked">••••••••</span>
            <span v-else class="value-text">{{ block.value }}</span>
            <button v-if="block.type === 'secret'" type="button" class="reveal" @click="toggleReveal(index)">
              {{ revealed.has(index) ? 'Hide' : 'Reveal' }}
            </button>
          </div>
        </li>
        <li v-if="!item.payload.content.length" class="empty">No content blocks.</li>
      </ul>
    </template>
  </main>
</template>

<style scoped>
.item-page {
  max-width: 40rem;
  margin: 3rem auto;
  padding: 0 1.5rem 3rem;
  font-family: system-ui, sans-serif;
}

.back-link {
  color: #3730a3;
  text-decoration: none;
  font-size: 0.9rem;
}

header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  margin-top: 1rem;
}

h1 {
  margin: 0;
  font-size: 1.4rem;
  word-break: break-word;
}

.category-tag {
  margin: 0.25rem 0 0;
  color: #666;
  font-size: 0.85rem;
}

.actions {
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}

.button-link {
  padding: 0.4rem 0.8rem;
  border-radius: 0.5rem;
  background: #3730a3;
  color: white;
  text-decoration: none;
  font-size: 0.85rem;
}

.danger {
  padding: 0.4rem 0.8rem;
  border: 1px solid #f4b8b8;
  border-radius: 0.5rem;
  background: white;
  color: #c1121f;
  cursor: pointer;
  font-size: 0.85rem;
}

.confirm-box {
  margin-top: 1rem;
  padding: 1rem;
  border: 1px solid #f0c36d;
  background: #fffaeb;
  border-radius: 0.5rem;
}

.confirm-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.confirm-actions button:not(.danger) {
  padding: 0.4rem 0.8rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
}

.blocks {
  list-style: none;
  margin: 1.5rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.block {
  padding: 0.75rem 0.9rem;
  border: 1px solid #e4e7ec;
  border-radius: 0.5rem;
  background: white;
}

.block-header {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  margin-bottom: 0.35rem;
}

.block-type {
  text-transform: uppercase;
  font-size: 0.7rem;
  font-weight: 700;
  color: #3730a3;
  letter-spacing: 0.03em;
}

.block-label {
  font-size: 0.85rem;
  color: #444;
  font-weight: 600;
}

.block-value {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  justify-content: space-between;
}

.value-text {
  word-break: break-word;
  white-space: pre-wrap;
}

.masked {
  letter-spacing: 0.15em;
  color: #444;
}

.reveal {
  padding: 0.2rem 0.5rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.4rem;
  background: white;
  cursor: pointer;
  font-size: 0.75rem;
  flex-shrink: 0;
}

.empty {
  color: #888;
  font-size: 0.9rem;
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.9rem;
  margin-top: 1rem;
}

.banner-error {
  background: #fef3f2;
  color: #c1121f;
}
</style>
