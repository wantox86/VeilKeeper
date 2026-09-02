<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore, type DecryptedVaultItem } from '../stores/vault'
import { useSettingsStore } from '../stores/settings'
import { copyToClipboard } from '../crypto/clipboard'

const route = useRoute()
const router = useRouter()
const vault = useVaultStore()
const settings = useSettingsStore()

const itemId = computed(() => Number(route.params.id))
const item = ref<DecryptedVaultItem | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const revealed = ref<Set<number>>(new Set())
const deleting = ref(false)
const showDeleteConfirm = ref(false)
const copyStatus = ref<Record<number, string>>({})
const copyStatusTimers: Record<number, ReturnType<typeof setTimeout>> = {}

/**
 * Clipboard Security (SPEC-BASE.md Section 29): every content block gets a
 * Copy button, not just `type === "secret"` -- everything in this vault is
 * sensitive, same reasoning Android's `ClipboardSecurity` wiring doc
 * comment gives. Never logs `block.value` anywhere, only a generic
 * feedback string derived from `copyToClipboard`'s boolean/string result
 * (see `crypto/clipboard.ts` for the real Clipboard API limitation this
 * result reflects).
 */
async function copyBlock(index: number, value: string): Promise<void> {
  const result = await copyToClipboard(value, settings.clipboardClearDelayMs)
  copyStatus.value = {
    ...copyStatus.value,
    [index]: result.copied
      ? result.clearScheduled
        ? 'Copied (clears automatically while this tab stays focused)'
        : 'Copied'
      : (result.error ?? 'Copy failed'),
  }
  if (copyStatusTimers[index]) clearTimeout(copyStatusTimers[index])
  copyStatusTimers[index] = setTimeout(() => {
    const next = { ...copyStatus.value }
    delete next[index]
    copyStatus.value = next
  }, 4000)
}

onBeforeUnmount(() => {
  for (const timer of Object.values(copyStatusTimers)) clearTimeout(timer)
})

/**
 * Attachment preview state (Web Sprint 6). Each "image" content block's
 * `value` field holds an attachment ID (CLAUDE.md's attachment-linking
 * decision) -- images are downloaded+decrypted lazily right after the item
 * loads and rendered via `URL.createObjectURL` on the decrypted `Blob`,
 * never as a base64 data-URI: a data-URI would put the *decoded* image
 * bytes directly in the DOM's `src` attribute as a long string, which can
 * end up logged/persisted by browser extensions, dev tools state, or (for
 * navigations, not relevant to an `<img>` src, but the same caution
 * applies) browser history -- a blob URL is an opaque local reference the
 * browser resolves in-memory and never persists anywhere on its own. Every
 * created blob URL is tracked in `imageUrls` and explicitly
 * `URL.revokeObjectURL`'d both when an image is removed and on unmount
 * (`onBeforeUnmount` below) so no blob URL (and the memory backing it)
 * outlives this view.
 */
const imageUrls = ref<Record<number, string>>({})
const imageLoading = ref<Record<number, boolean>>({})
const imageError = ref<Record<number, string>>({})
const deletingAttachment = ref<number | null>(null) // index of the image block pending a remove-confirm
const attachmentActionError = ref<string | null>(null)

function revokeAllImageUrls(): void {
  for (const url of Object.values(imageUrls.value)) URL.revokeObjectURL(url)
  imageUrls.value = {}
}

onBeforeUnmount(revokeAllImageUrls)

async function loadImage(index: number, attachmentId: number): Promise<void> {
  if (imageUrls.value[index]) return
  imageLoading.value = { ...imageLoading.value, [index]: true }
  imageError.value = { ...imageError.value, [index]: '' }
  try {
    const { blob } = await vault.downloadAttachment(itemId.value, attachmentId)
    imageUrls.value = { ...imageUrls.value, [index]: URL.createObjectURL(blob) }
  } catch {
    imageError.value = { ...imageError.value, [index]: vault.errorMessage ?? 'Failed to load image.' }
  } finally {
    imageLoading.value = { ...imageLoading.value, [index]: false }
  }
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  revokeAllImageUrls()
  try {
    item.value = await vault.fetchItem(itemId.value)
    if (!vault.categories.length) {
      await vault.fetchCategories()
    }
    item.value.payload.content.forEach((block, index) => {
      if (block.type === 'image') {
        const attachmentId = Number(block.value)
        if (Number.isFinite(attachmentId)) void loadImage(index, attachmentId)
      }
    })
  } catch {
    loadError.value = vault.errorMessage ?? 'Failed to load this item.'
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** Deletes the attachment server-side, removes its content block, and persists the item -- see `VaultItemFormView.vue`'s doc comment for why this is an immediate (not draft) delete. */
async function confirmDeleteAttachment(index: number): Promise<void> {
  if (!item.value) return
  const block = item.value.payload.content[index]
  const attachmentId = Number(block.value)
  attachmentActionError.value = null
  try {
    await vault.deleteAttachment(itemId.value, attachmentId)
    const newContent = item.value.payload.content.filter((_, i) => i !== index)
    item.value = await vault.updateItem(itemId.value, {
      title: item.value.payload.title,
      content: newContent,
    })
    await load() // re-fetch + reload remaining image previews so indices stay in sync
  } catch {
    attachmentActionError.value = vault.errorMessage ?? 'Failed to remove attachment.'
  } finally {
    deletingAttachment.value = null
  }
}

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

      <p v-if="attachmentActionError" class="banner banner-error" role="alert">{{ attachmentActionError }}</p>

      <ul class="blocks">
        <li v-for="(block, index) in item.payload.content" :key="index" class="block">
          <template v-if="block.type === 'image'">
            <div class="block-header">
              <span class="block-type">image</span>
            </div>
            <div class="attachment-card">
              <img
                v-if="imageUrls[index]"
                :src="imageUrls[index]"
                alt="Decrypted attachment preview"
                class="attachment-image"
              />
              <div v-else-if="imageLoading[index]" class="attachment-placeholder">Decrypting…</div>
              <div v-else-if="imageError[index]" class="attachment-placeholder attachment-error">
                {{ imageError[index] }}
              </div>
              <div v-else class="attachment-placeholder">Unavailable</div>

              <div class="attachment-actions">
                <div v-if="deletingAttachment === index" class="confirm-inline">
                  <span>Delete this image permanently?</span>
                  <button type="button" class="danger" @click="confirmDeleteAttachment(index)">
                    Confirm
                  </button>
                  <button type="button" @click="deletingAttachment = null">Cancel</button>
                </div>
                <button v-else type="button" class="reveal" @click="deletingAttachment = index">
                  Delete
                </button>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="block-header">
              <span class="block-type">{{ block.type }}</span>
              <span v-if="block.label" class="block-label">{{ block.label }}</span>
            </div>
            <div class="block-value">
              <span v-if="block.type === 'secret' && !revealed.has(index)" class="masked">••••••••</span>
              <span v-else class="value-text">{{ block.value }}</span>
              <span class="block-buttons">
                <button
                  v-if="block.type === 'secret'"
                  type="button"
                  class="reveal"
                  @click="toggleReveal(index)"
                >
                  {{ revealed.has(index) ? 'Hide' : 'Show' }}
                </button>
                <button type="button" class="reveal" @click="copyBlock(index, block.value)">Copy</button>
              </span>
            </div>
            <p v-if="copyStatus[index]" class="copy-status">{{ copyStatus[index] }}</p>
          </template>
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

.block-buttons {
  display: flex;
  gap: 0.35rem;
  flex-shrink: 0;
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

.copy-status {
  margin: 0.35rem 0 0;
  font-size: 0.75rem;
  color: #1a7f37;
}

.empty {
  color: #888;
  font-size: 0.9rem;
}

.attachment-card {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.attachment-image {
  max-width: 100%;
  max-height: 320px;
  border-radius: 0.4rem;
  object-fit: contain;
  background: #f2f4f7;
}

.attachment-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 160px;
  height: 120px;
  border-radius: 0.4rem;
  background: #f2f4f7;
  color: #888;
  font-size: 0.8rem;
  text-align: center;
  padding: 0.5rem;
}

.attachment-error {
  color: #c1121f;
}

.attachment-actions {
  flex-shrink: 0;
}

.confirm-inline {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8rem;
}

.confirm-inline .danger {
  padding: 0.3rem 0.6rem;
  border: 1px solid #f4b8b8;
  border-radius: 0.4rem;
  background: white;
  color: #c1121f;
  cursor: pointer;
  font-size: 0.75rem;
}

.confirm-inline button:not(.danger) {
  padding: 0.3rem 0.6rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.4rem;
  background: white;
  cursor: pointer;
  font-size: 0.75rem;
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
