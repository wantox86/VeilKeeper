<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore } from '../stores/vault'

const route = useRoute()
const router = useRouter()
const vault = useVaultStore()

const categoryId = computed(() => Number(route.params.id))
const category = computed(() => vault.categories.find((c) => c.id === categoryId.value) ?? null)
const loading = ref(true)

const renaming = ref(false)
const renameValue = ref('')
const renameSubmitting = ref(false)

const showDeleteConfirm = ref(false)
const reassignTo = ref<string>('') // '' = default (move to Uncategorized)
const deleteSubmitting = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    if (!vault.categories.length) {
      await vault.fetchCategories()
    }
    await vault.fetchItems(categoryId.value)
  } catch {
    // vault.errorMessage already holds a user-facing message (rendered in
    // the banner below, and the !category branch handles the not-found
    // case) -- swallow here so this never surfaces as an unhandled promise
    // rejection.
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startRename(): void {
  renameValue.value = category.value?.name ?? ''
  renaming.value = true
}

async function submitRename(): Promise<void> {
  const name = renameValue.value.trim()
  if (!name) return
  renameSubmitting.value = true
  try {
    await vault.renameCategory(categoryId.value, name)
    renaming.value = false
  } catch {
    // vault.errorMessage already holds a user-facing message.
  } finally {
    renameSubmitting.value = false
  }
}

const otherCategories = computed(() =>
  vault.categories.filter((c) => c.id !== categoryId.value && !c.is_uncategorized),
)

async function confirmDelete(): Promise<void> {
  deleteSubmitting.value = true
  try {
    const target = reassignTo.value ? Number(reassignTo.value) : undefined
    await vault.deleteCategory(categoryId.value, target)
    await router.push('/dashboard')
  } catch {
    // vault.errorMessage already holds a user-facing message; stay on page.
  } finally {
    deleteSubmitting.value = false
    showDeleteConfirm.value = false
  }
}
</script>

<template>
  <main class="category-page">
    <RouterLink to="/dashboard" class="back-link">&larr; Home</RouterLink>

    <p v-if="vault.errorMessage" class="banner banner-error" role="alert">{{ vault.errorMessage }}</p>

    <div v-if="loading">Loading…</div>
    <div v-else-if="!category" class="banner banner-error">
      This category doesn't exist, or you don't have access to it.
    </div>
    <template v-else>
      <header>
        <div v-if="!renaming">
          <h1>{{ category.name }}</h1>
        </div>
        <form v-else class="rename-form" @submit.prevent="submitRename">
          <input v-model="renameValue" type="text" maxlength="100" required />
          <button type="submit" :disabled="renameSubmitting">Save</button>
          <button type="button" @click="renaming = false">Cancel</button>
        </form>

        <div v-if="!renaming" class="actions">
          <RouterLink :to="`/items/new?category=${categoryId}`" class="button-link">+ New item</RouterLink>
          <button v-if="!category.is_uncategorized" type="button" @click="startRename">Rename</button>
          <button
            v-if="!category.is_uncategorized"
            type="button"
            class="danger"
            @click="showDeleteConfirm = true"
          >
            Delete
          </button>
        </div>
      </header>

      <div v-if="showDeleteConfirm" class="confirm-box">
        <p>
          Delete <strong>{{ category.name }}</strong
          >? Its {{ category.item_count }} item(s) will be moved, not deleted.
        </p>
        <label for="reassignTo">Move items to</label>
        <select id="reassignTo" v-model="reassignTo">
          <option value="">Uncategorized (default)</option>
          <option v-for="c in otherCategories" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <div class="confirm-actions">
          <button type="button" class="danger" :disabled="deleteSubmitting" @click="confirmDelete">
            {{ deleteSubmitting ? 'Deleting…' : 'Confirm delete' }}
          </button>
          <button type="button" @click="showDeleteConfirm = false">Cancel</button>
        </div>
      </div>

      <ul class="item-list">
        <li v-for="item in vault.items" :key="item.id">
          <RouterLink :to="`/items/${item.id}`" class="item-row">{{ item.payload.title }}</RouterLink>
        </li>
        <li v-if="!vault.items.length" class="empty">No items in this category yet.</li>
      </ul>
    </template>
  </main>
</template>

<style scoped>
.category-page {
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
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1rem;
}

h1 {
  margin: 0;
  font-size: 1.4rem;
}

.actions {
  display: flex;
  gap: 0.5rem;
}

.actions button,
.confirm-actions button {
  padding: 0.4rem 0.8rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
  font-size: 0.85rem;
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
  color: #c1121f;
  border-color: #f4b8b8 !important;
}

.rename-form {
  display: flex;
  gap: 0.5rem;
}

.rename-form input {
  padding: 0.4rem 0.6rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
}

.confirm-box {
  margin-top: 1rem;
  padding: 1rem;
  border: 1px solid #f0c36d;
  background: #fffaeb;
  border-radius: 0.5rem;
}

.confirm-box select {
  display: block;
  margin: 0.5rem 0;
  padding: 0.4rem 0.6rem;
  border-radius: 0.5rem;
  border: 1px solid #d0d5dd;
}

.confirm-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.item-list {
  list-style: none;
  margin: 1.5rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.item-row {
  display: block;
  padding: 0.65rem 0.9rem;
  border: 1px solid #e4e7ec;
  border-radius: 0.5rem;
  text-decoration: none;
  color: inherit;
  background: white;
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
