<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore, type DecryptedVaultItem } from '../stores/vault'
import { useSettingsStore } from '../stores/settings'
import { copyToClipboard } from '../crypto/clipboard'
import AppLayout from '../components/AppLayout.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import ErrorState from '../components/ErrorState.vue'
import Icon from '../components/Icon.vue'

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

const notFoundMessage = "This item doesn't exist, or you don't have access to it."

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
  <AppLayout>
    <div class="item-page">
      <LoadingState v-if="loading" full-height />
      <ErrorState v-else-if="loadError || !item" :message="loadError ?? notFoundMessage" />
      <template v-else>
        <header>
          <div>
            <h1>{{ item.payload.title }}</h1>
            <p class="category-tag">{{ categoryName(item.categoryId) }}</p>
          </div>
          <div class="actions">
            <RouterLink :to="`/items/${item.id}/edit`" class="button-link">Edit</RouterLink>
            <button type="button" class="danger" aria-label="Delete item" @click="showDeleteConfirm = true">
              <Icon name="trash" :size="16" />
              Delete
            </button>
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

        <p v-if="attachmentActionError" class="banner banner-error" role="alert">
          {{ attachmentActionError }}
        </p>

        <ul v-if="item.payload.content.length" class="blocks">
          <li v-for="(block, index) in item.payload.content" :key="index" class="block">
            <template v-if="block.type === 'image'">
              <div class="block-header">
                <Icon name="image" :size="14" class="block-icon" />
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
                  <button
                    v-else
                    type="button"
                    class="reveal"
                    aria-label="Delete image"
                    @click="deletingAttachment = index"
                  >
                    <Icon name="trash" :size="14" />
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
                    :aria-label="revealed.has(index) ? 'Hide value' : 'Show value'"
                    @click="toggleReveal(index)"
                  >
                    <Icon :name="revealed.has(index) ? 'eye-off' : 'eye'" :size="14" />
                    {{ revealed.has(index) ? 'Hide' : 'Show' }}
                  </button>
                  <button
                    type="button"
                    class="reveal"
                    aria-label="Copy value"
                    @click="copyBlock(index, block.value)"
                  >
                    <Icon name="copy" :size="14" />
                    Copy
                  </button>
                </span>
              </div>
              <p v-if="copyStatus[index]" class="copy-status">{{ copyStatus[index] }}</p>
            </template>
          </li>
        </ul>
        <EmptyState v-else title="No content blocks" />
      </template>
    </div>
  </AppLayout>
</template>

<style scoped>
.item-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

h1 {
  margin: 0;
  font-size: var(--font-size-title-lg);
  word-break: break-word;
}

.category-tag {
  margin: var(--space-xs) 0 0;
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-body-md);
}

.actions {
  display: flex;
  gap: var(--space-sm);
  flex-shrink: 0;
}

.button-link {
  padding: 0.4rem 0.8rem;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  text-decoration: none;
  font-size: var(--font-size-label-lg);
  font-weight: 600;
}

.danger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.4rem 0.8rem;
  border: 1px solid var(--color-error);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-error);
  cursor: pointer;
  font-size: var(--font-size-label-lg);
}

.confirm-box {
  padding: var(--space-md);
  border: 1px solid var(--color-warning-border);
  background: var(--color-warning-container);
  border-radius: var(--radius-md);
  color: var(--color-warning);
}

.confirm-actions {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.confirm-actions button:not(.danger) {
  padding: 0.4rem 0.8rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
}

.blocks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.block {
  padding: 0.75rem 0.9rem;
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.block-header {
  display: flex;
  gap: var(--space-xs);
  align-items: center;
  margin-bottom: 0.35rem;
}

.block-icon {
  color: var(--color-primary);
}

.block-type {
  text-transform: uppercase;
  font-size: var(--font-size-label-md);
  font-weight: 700;
  color: var(--color-primary);
  letter-spacing: 0.03em;
}

.block-label {
  font-size: var(--font-size-body-md);
  color: var(--color-on-surface-variant);
  font-weight: 600;
}

.block-value {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  justify-content: space-between;
}

.value-text {
  word-break: break-word;
  white-space: pre-wrap;
}

.masked {
  letter-spacing: 0.15em;
  color: var(--color-on-surface-variant);
}

.block-buttons {
  display: flex;
  gap: var(--space-xs);
  flex-shrink: 0;
}

.reveal {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.2rem 0.5rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  font-size: var(--font-size-label-md);
  flex-shrink: 0;
}

.copy-status {
  margin: 0.35rem 0 0;
  font-size: var(--font-size-label-md);
  color: var(--color-success);
}

.attachment-card {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.attachment-image {
  max-width: 100%;
  max-height: 320px;
  border-radius: var(--radius-sm);
  object-fit: contain;
  background: var(--color-surface-variant);
}

.attachment-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 160px;
  height: 120px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-variant);
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-label-lg);
  text-align: center;
  padding: var(--space-sm);
}

.attachment-error {
  color: var(--color-error);
}

.attachment-actions {
  flex-shrink: 0;
}

.confirm-inline {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--font-size-label-lg);
}

.confirm-inline .danger {
  padding: 0.3rem 0.6rem;
  border: 1px solid var(--color-error);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-error);
  cursor: pointer;
  font-size: var(--font-size-label-md);
}

.confirm-inline button:not(.danger) {
  padding: 0.3rem 0.6rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  font-size: var(--font-size-label-md);
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
