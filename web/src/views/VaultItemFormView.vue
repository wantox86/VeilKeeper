<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore } from '../stores/vault'
import type { ContentBlock } from '../types/vault'

/**
 * Add/Edit vault item form. Sprint 3 scope: content block types "text" /
 * "secret" / "note" only -- "image" (attachment) is Web Sprint 6, not built
 * here (CLAUDE.md Web Sprint roadmap, Sprint 3 task explicitly excludes it).
 */
const route = useRoute()
const router = useRouter()
const vault = useVaultStore()

const editingId = computed(() => (route.name === 'item-edit' ? Number(route.params.id) : null))
const isEdit = computed(() => editingId.value !== null)

const title = ref('')
const categoryId = ref<number | null>(null)
const content = ref<ContentBlock[]>([{ type: 'text', label: '', value: '' }])
const loading = ref(true)
const submitting = ref(false)
const formError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    if (!vault.categories.length) {
      await vault.fetchCategories()
    }

    if (isEdit.value && editingId.value !== null) {
      const item = await vault.fetchItem(editingId.value)
      title.value = item.payload.title
      categoryId.value = item.categoryId
      content.value = item.payload.content.length
        ? item.payload.content.map((b) => ({ ...b }))
        : [{ type: 'text', label: '', value: '' }]
    } else {
      const queryCategory = Number(route.query.category)
      categoryId.value =
        Number.isFinite(queryCategory) && queryCategory > 0
          ? queryCategory
          : (vault.categories[0]?.id ?? null)
    }
  } catch {
    formError.value = vault.errorMessage ?? 'Failed to load this item.'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function addBlock(): void {
  content.value.push({ type: 'text', label: '', value: '' })
}

function removeBlock(index: number): void {
  content.value.splice(index, 1)
}

async function onSubmit(): Promise<void> {
  formError.value = null

  const trimmedTitle = title.value.trim()
  if (!trimmedTitle) {
    formError.value = 'Title is required.'
    return
  }
  if (categoryId.value === null) {
    formError.value = 'Choose a category.'
    return
  }
  const blocks = content.value
    .map((b) => ({ type: b.type, label: b.label?.trim() ? b.label.trim() : null, value: b.value }))
    .filter((b) => b.value.trim().length > 0)
  if (!blocks.length) {
    formError.value = 'Add at least one content block with a value.'
    return
  }

  submitting.value = true
  try {
    const payload = { title: trimmedTitle, content: blocks }
    if (isEdit.value && editingId.value !== null) {
      const updated = await vault.updateItem(editingId.value, payload, categoryId.value)
      await router.push(`/items/${updated.id}`)
    } else {
      const created = await vault.createItem(categoryId.value, payload)
      await router.push(`/items/${created.id}`)
    }
  } catch {
    formError.value = vault.errorMessage ?? 'Failed to save this item.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="form-page">
    <RouterLink to="/dashboard" class="back-link">&larr; Home</RouterLink>

    <h1>{{ isEdit ? 'Edit item' : 'New item' }}</h1>

    <div v-if="loading">Loading…</div>
    <form v-else @submit.prevent="onSubmit">
      <p v-if="formError" class="banner banner-error" role="alert">{{ formError }}</p>

      <label for="title">Title</label>
      <input id="title" v-model="title" type="text" required maxlength="200" />

      <label for="category">Category</label>
      <select id="category" v-model.number="categoryId" required>
        <option v-for="c in vault.categories" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>

      <div class="blocks-header">
        <h2>Content</h2>
        <button type="button" @click="addBlock">+ Add block</button>
      </div>

      <div v-for="(block, index) in content" :key="index" class="block-editor">
        <select v-model="block.type">
          <option value="text">Text</option>
          <option value="secret">Secret</option>
          <option value="note">Note</option>
        </select>
        <input v-model="block.label" type="text" placeholder="Label (optional)" maxlength="100" />
        <textarea v-model="block.value" placeholder="Value" rows="2"></textarea>
        <button type="button" class="remove" :disabled="content.length === 1" @click="removeBlock(index)">
          Remove
        </button>
      </div>

      <button type="submit" class="submit" :disabled="submitting">
        {{ submitting ? 'Saving…' : isEdit ? 'Save changes' : 'Create item' }}
      </button>
    </form>
  </main>
</template>

<style scoped>
.form-page {
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

h1 {
  font-size: 1.4rem;
  margin: 1rem 0 1.5rem;
}

label {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
  margin-top: 1rem;
}

input,
select,
textarea {
  width: 100%;
  padding: 0.55rem 0.7rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  font-size: 1rem;
  font-family: inherit;
  margin-top: 0.3rem;
  box-sizing: border-box;
}

.blocks-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 1.5rem;
}

.blocks-header h2 {
  font-size: 1rem;
  margin: 0;
}

.blocks-header button {
  padding: 0.35rem 0.7rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
  font-size: 0.8rem;
}

.block-editor {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 0.75rem;
  border: 1px solid #e4e7ec;
  border-radius: 0.5rem;
  margin-top: 0.6rem;
}

.block-editor select,
.block-editor input,
.block-editor textarea {
  margin-top: 0;
}

.remove {
  align-self: flex-end;
  padding: 0.3rem 0.6rem;
  border: 1px solid #f4b8b8;
  border-radius: 0.4rem;
  background: white;
  color: #c1121f;
  cursor: pointer;
  font-size: 0.75rem;
}

.remove:disabled {
  opacity: 0.5;
  cursor: default;
}

.submit {
  margin-top: 1.5rem;
  padding: 0.65rem;
  border: none;
  border-radius: 0.5rem;
  background: #3730a3;
  color: white;
  font-size: 1rem;
  cursor: pointer;
  width: 100%;
}

.submit:disabled {
  opacity: 0.6;
  cursor: default;
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.9rem;
  margin-bottom: 1rem;
}

.banner-error {
  background: #fef3f2;
  color: #c1121f;
}
</style>
