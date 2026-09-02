<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore } from '../stores/vault'
import AppLayout from '../components/AppLayout.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import ErrorState from '../components/ErrorState.vue'
import Icon from '../components/Icon.vue'

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
  <AppLayout>
    <div class="category-page">
      <p v-if="vault.errorMessage" class="banner banner-error" role="alert">{{ vault.errorMessage }}</p>

      <LoadingState v-if="loading" full-height />
      <ErrorState
        v-else-if="!category"
        message="This category doesn't exist, or you don't have access to it."
      />
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
            <RouterLink :to="`/items/new?category=${categoryId}`" class="button-link">
              <Icon name="plus" :size="16" />
              New item
            </RouterLink>
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

        <ul v-if="vault.items.length" class="item-list">
          <li v-for="item in vault.items" :key="item.id">
            <RouterLink :to="`/items/${item.id}`" class="item-row">{{ item.payload.title }}</RouterLink>
          </li>
        </ul>
        <EmptyState
          v-else
          title="No items in this category yet"
          action-label="+ New item"
          @action="router.push(`/items/new?category=${categoryId}`)"
        />
      </template>
    </div>
  </AppLayout>
</template>

<style scoped>
.category-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

h1 {
  margin: 0;
  font-size: var(--font-size-title-lg);
}

.actions {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.actions button,
.confirm-actions button {
  padding: 0.4rem 0.8rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  font-size: var(--font-size-label-lg);
}

.button-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.4rem 0.8rem;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  text-decoration: none;
  font-size: var(--font-size-label-lg);
  font-weight: 600;
}

.danger {
  color: var(--color-error) !important;
  border-color: var(--color-error) !important;
}

.rename-form {
  display: flex;
  gap: var(--space-sm);
}

.rename-form input {
  padding: 0.4rem 0.6rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
}

.confirm-box {
  padding: var(--space-md);
  border: 1px solid var(--color-warning-border);
  background: var(--color-warning-container);
  border-radius: var(--radius-md);
  color: var(--color-warning);
}

.confirm-box select {
  display: block;
  margin: var(--space-sm) 0;
  padding: 0.4rem 0.6rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-outline);
  background: var(--color-surface);
  color: var(--color-on-surface);
}

.confirm-actions {
  display: flex;
  gap: var(--space-sm);
}

.item-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.item-row {
  display: block;
  padding: 0.65rem 0.9rem;
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  text-decoration: none;
  color: inherit;
  background: var(--color-surface);
}

.item-row:hover {
  border-color: var(--color-primary);
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-body-md);
}

.banner-error {
  background: var(--color-error-container);
  color: var(--color-on-error-container);
}
</style>
