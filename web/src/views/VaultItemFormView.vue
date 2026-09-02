<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useVaultStore } from '../stores/vault'
import { compressImage } from '../services/imageCompressor'
import type { ContentBlock } from '../types/vault'
import AppLayout from '../components/AppLayout.vue'
import LoadingState from '../components/LoadingState.vue'
import Icon from '../components/Icon.vue'

/**
 * Add/Edit vault item form. Web Sprint 6 adds "image" content blocks
 * (attachments) -- Sprint 3 originally excluded them (CLAUDE.md Web Sprint
 * roadmap).
 *
 * **Attachment flow, mirroring Android Sprint 5's own documented decision**
 * (`android/app/.../ui/vault/AddItemViewModel.kt`, CLAUDE.md's Sprint 5 "Add
 * Item flow decision"): the backend endpoint is
 * `/vault/items/{id}/attachments` -- attachments can only be uploaded
 * against an *already-existing* item. So:
 * - **New item**: picked-but-not-yet-uploaded images are held in memory as
 *   `pendingImages`. `onSubmit` creates the item with the non-image blocks
 *   first, uploads each pending image against the new item id, then makes
 *   one more `updateItem` call appending the resulting "image" blocks.
 *   Disclosed limitation (same as Android): no rollback if an upload fails
 *   partway through save -- the item already exists with whatever uploaded
 *   successfully; a specific error message is shown rather than silently
 *   losing state. A full transactional multi-attachment save was judged
 *   more machinery than this single-user homelab app's failure modes
 *   justify (SPEC-BASE.md Section 56 Rule 1).
 * - **Existing item (edit)**: a newly picked image is uploaded immediately
 *   (the item already exists) and appended to `content` right away. Removing
 *   an *existing* image block deletes the attachment server-side
 *   immediately (there is nothing to "undo" a plaintext-local draft for --
 *   the file only ever existed as ciphertext on the server) and is gated
 *   behind a confirm, since it's an immediate destructive action, unlike
 *   removing a draft text/secret/note block.
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

// Non-image blocks are the only ones edited via the generic block editor
// below -- image blocks get their own dedicated UI (thumbnail + remove),
// editing "value" (an attachment id) by hand would make no sense.
const textBlockIndices = computed(() =>
  content.value.reduce<number[]>((acc, b, i) => {
    if (b.type !== 'image') acc.push(i)
    return acc
  }, []),
)
const imageBlockIndices = computed(() =>
  content.value.reduce<number[]>((acc, b, i) => {
    if (b.type === 'image') acc.push(i)
    return acc
  }, []),
)

interface PendingImage {
  file: File
  previewUrl: string
}
const pendingImages = ref<PendingImage[]>([])
const attachmentBusy = ref(false)
const attachmentError = ref<string | null>(null)
const removingAttachment = ref<number | null>(null) // index of the image block pending a remove-confirm

// Existing (already-uploaded) image blocks' decrypted previews, keyed by
// content index. Loaded lazily so a form with no images does no extra work.
const existingImagePreviews = ref<Record<number, string>>({})
const existingImageLoading = ref<Record<number, boolean>>({})

function revokeAllObjectUrls(): void {
  for (const p of pendingImages.value) URL.revokeObjectURL(p.previewUrl)
  for (const url of Object.values(existingImagePreviews.value)) URL.revokeObjectURL(url)
}

onBeforeUnmount(revokeAllObjectUrls)

async function loadExistingImagePreview(index: number, attachmentId: number): Promise<void> {
  if (existingImagePreviews.value[index] || !editingId.value) return
  existingImageLoading.value = { ...existingImageLoading.value, [index]: true }
  try {
    const { blob } = await vault.downloadAttachment(editingId.value, attachmentId)
    existingImagePreviews.value = { ...existingImagePreviews.value, [index]: URL.createObjectURL(blob) }
  } catch {
    // Leave unset -- template falls back to a "failed to load" hint.
  } finally {
    existingImageLoading.value = { ...existingImageLoading.value, [index]: false }
  }
}

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
      content.value.forEach((b, i) => {
        if (b.type === 'image') {
          const attachmentId = Number(b.value)
          if (Number.isFinite(attachmentId)) void loadExistingImagePreview(i, attachmentId)
        }
      })
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

function onPickImages(event: Event): void {
  attachmentError.value = null
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files?.length) return
  for (const file of Array.from(files)) {
    pendingImages.value.push({ file, previewUrl: URL.createObjectURL(file) })
  }
  input.value = '' // allow picking the same file again later
}

function removePendingImage(index: number): void {
  const [removed] = pendingImages.value.splice(index, 1)
  if (removed) URL.revokeObjectURL(removed.previewUrl)
}

/**
 * Compresses (Canvas API downscale-to-JPEG, `services/imageCompressor.ts`)
 * and encrypts a picked file, uploading it against `itemId`. Falls back to
 * uploading the original bytes/mime-type if compression isn't possible in
 * this browser (`compressImage` returns null) -- never silently drops the
 * pick.
 */
async function uploadPendingImage(itemId: number, file: File): Promise<ContentBlock> {
  const compressed = await compressImage(file)
  const data = compressed ? compressed.data : new Uint8Array(await file.arrayBuffer())
  const mimeType = compressed ? compressed.mimeType : file.type || 'application/octet-stream'
  const attachmentId = await vault.uploadAttachment(itemId, data, mimeType, file.name)
  return { type: 'image', label: null, value: String(attachmentId) }
}

/** Existing item only -- uploads immediately and appends to `content`, since the item already exists server-side. */
async function uploadImageNow(): Promise<void> {
  if (!editingId.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file || !editingId.value) return
    attachmentBusy.value = true
    attachmentError.value = null
    try {
      const block = await uploadPendingImage(editingId.value, file)
      content.value.push(block)
      const index = content.value.length - 1
      const attachmentId = Number(block.value)
      void loadExistingImagePreview(index, attachmentId)
    } catch {
      attachmentError.value = vault.errorMessage ?? 'Failed to upload image.'
    } finally {
      attachmentBusy.value = false
    }
  }
  input.click()
}

async function confirmRemoveExistingImage(index: number): Promise<void> {
  if (!editingId.value) return
  const block = content.value[index]
  const attachmentId = Number(block.value)
  attachmentBusy.value = true
  attachmentError.value = null
  try {
    await vault.deleteAttachment(editingId.value, attachmentId)
    if (existingImagePreviews.value[index]) {
      URL.revokeObjectURL(existingImagePreviews.value[index])
      const next = { ...existingImagePreviews.value }
      delete next[index]
      existingImagePreviews.value = next
    }
    content.value.splice(index, 1)
  } catch {
    attachmentError.value = vault.errorMessage ?? 'Failed to remove attachment.'
  } finally {
    attachmentBusy.value = false
    removingAttachment.value = null
  }
}

async function onSubmit(): Promise<void> {
  formError.value = null
  attachmentError.value = null

  const trimmedTitle = title.value.trim()
  if (!trimmedTitle) {
    formError.value = 'Title is required.'
    return
  }
  if (categoryId.value === null) {
    formError.value = 'Choose a category.'
    return
  }
  const nonImageBlocks = content.value
    .filter((b) => b.type !== 'image')
    .map((b) => ({ type: b.type, label: b.label?.trim() ? b.label.trim() : null, value: b.value }))
    .filter((b) => b.value.trim().length > 0)
  const existingImageBlocks = content.value.filter((b) => b.type === 'image')

  // An image alone satisfies the content requirement -- mirrors Android's
  // AddItemViewModel rule exactly.
  if (!nonImageBlocks.length && !existingImageBlocks.length && !pendingImages.value.length) {
    formError.value = 'Add at least one content block or image.'
    return
  }

  submitting.value = true
  try {
    if (isEdit.value && editingId.value !== null) {
      // Edit mode: any new images were already uploaded immediately via
      // uploadImageNow(), so `content.value` already reflects them --
      // just persist the current block list as-is.
      const payload = { title: trimmedTitle, content: [...nonImageBlocks, ...existingImageBlocks] }
      const updated = await vault.updateItem(editingId.value, payload, categoryId.value)
      await router.push(`/items/${updated.id}`)
      return
    }

    // Create mode: create the item with non-image blocks first (the
    // backend endpoint for attachments requires an existing item id).
    const created = await vault.createItem(categoryId.value, { title: trimmedTitle, content: nonImageBlocks })

    if (pendingImages.value.length) {
      const uploadedBlocks: ContentBlock[] = []
      for (const pending of pendingImages.value) {
        try {
          uploadedBlocks.push(await uploadPendingImage(created.id, pending.file))
        } catch {
          formError.value = `Item created, but "${pending.file.name}" failed to upload. Edit the item to retry.`
          // Stop here (disclosed limitation): the item already exists with
          // whatever uploaded successfully so far, not attempting a
          // transactional rollback (see doc comment above).
          await router.push(`/items/${created.id}`)
          return
        }
      }
      await vault.updateItem(created.id, {
        title: trimmedTitle,
        content: [...nonImageBlocks, ...uploadedBlocks],
      })
    }

    await router.push(`/items/${created.id}`)
  } catch {
    formError.value = vault.errorMessage ?? 'Failed to save this item.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="form-page">
      <h1>{{ isEdit ? 'Edit item' : 'New item' }}</h1>

      <LoadingState v-if="loading" full-height />
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
          <button type="button" @click="addBlock">
            <Icon name="plus" :size="14" />
            Add block
          </button>
        </div>

        <div v-for="index in textBlockIndices" :key="index" class="block-editor">
          <select v-model="content[index].type">
            <option value="text">Text</option>
            <option value="secret">Secret</option>
            <option value="note">Note</option>
          </select>
          <input v-model="content[index].label" type="text" placeholder="Label (optional)" maxlength="100" />
          <textarea v-model="content[index].value" placeholder="Value" rows="2"></textarea>
          <button
            type="button"
            class="remove"
            :disabled="content.length === 1"
            aria-label="Remove block"
            @click="removeBlock(index)"
          >
            <Icon name="trash" :size="14" />
            Remove
          </button>
        </div>

        <div class="blocks-header">
          <h2>Images</h2>
          <label v-if="!isEdit" class="file-picker-label">
            <Icon name="plus" :size="14" />
            Add image
            <input type="file" accept="image/*" multiple class="file-picker-input" @change="onPickImages" />
          </label>
          <button v-else type="button" :disabled="attachmentBusy" @click="uploadImageNow">
            <Icon name="plus" :size="14" />
            {{ attachmentBusy ? 'Uploading…' : 'Add image' }}
          </button>
        </div>
        <p v-if="attachmentError" class="banner banner-error" role="alert">{{ attachmentError }}</p>

        <!-- Existing (already-uploaded) image blocks -- edit mode only. -->
        <div v-for="index in imageBlockIndices" :key="`img-${index}`" class="image-block">
          <img
            v-if="existingImagePreviews[index]"
            :src="existingImagePreviews[index]"
            alt="Attachment preview"
            class="image-thumb"
          />
          <div v-else-if="existingImageLoading[index]" class="image-thumb image-thumb-placeholder">
            Loading…
          </div>
          <div v-else class="image-thumb image-thumb-placeholder">Preview unavailable</div>

          <div v-if="removingAttachment === index" class="confirm-inline">
            <span>Remove this image permanently?</span>
            <button
              type="button"
              class="danger"
              :disabled="attachmentBusy"
              @click="confirmRemoveExistingImage(index)"
            >
              Confirm
            </button>
            <button type="button" @click="removingAttachment = null">Cancel</button>
          </div>
          <button
            v-else
            type="button"
            class="remove"
            aria-label="Remove image"
            @click="removingAttachment = index"
          >
            <Icon name="trash" :size="14" />
            Remove
          </button>
        </div>

        <!-- Newly picked, not-yet-uploaded images -- create mode only (uploaded on submit). -->
        <div v-for="(pending, index) in pendingImages" :key="`pending-${index}`" class="image-block">
          <img :src="pending.previewUrl" alt="Pending image preview" class="image-thumb" />
          <button
            type="button"
            class="remove"
            aria-label="Remove pending image"
            @click="removePendingImage(index)"
          >
            <Icon name="trash" :size="14" />
            Remove
          </button>
        </div>

        <p v-if="!imageBlockIndices.length && !pendingImages.length" class="empty">No images.</p>

        <button type="submit" class="submit" :disabled="submitting">
          {{ submitting ? 'Saving…' : isEdit ? 'Save changes' : 'Create item' }}
        </button>
      </form>
    </div>
  </AppLayout>
</template>

<style scoped>
.form-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

h1 {
  font-size: var(--font-size-title-lg);
  margin: 0;
}

label {
  display: block;
  font-size: var(--font-size-label-lg);
  font-weight: 600;
  margin-top: var(--space-md);
}

input,
select,
textarea {
  width: 100%;
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  font-size: 1rem;
  font-family: inherit;
  margin-top: 0.3rem;
  box-sizing: border-box;
  background: var(--color-surface);
  color: var(--color-on-surface);
}

.blocks-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-lg);
}

.blocks-header h2 {
  font-size: var(--font-size-title-md);
  margin: 0;
}

.blocks-header button,
.file-picker-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.35rem 0.7rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  font-size: var(--font-size-label-md);
  font-weight: 600;
}

.block-editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-sm);
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  margin-top: var(--space-sm);
}

.block-editor select,
.block-editor input,
.block-editor textarea {
  margin-top: 0;
}

.remove {
  align-self: flex-end;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.3rem 0.6rem;
  border: 1px solid var(--color-error);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-error);
  cursor: pointer;
  font-size: var(--font-size-label-md);
}

.remove:disabled {
  opacity: 0.5;
  cursor: default;
}

.file-picker-label {
  margin-top: 0;
  position: relative;
}

.file-picker-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
}

.image-block {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm);
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  margin-top: var(--space-sm);
}

.image-thumb {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  background: var(--color-surface-variant);
  flex-shrink: 0;
}

.image-thumb-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  font-size: var(--font-size-label-md);
  color: var(--color-on-surface-variant);
  padding: var(--space-xs);
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

.submit {
  margin-top: var(--space-lg);
  padding: 0.65rem;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  width: 100%;
}

.submit:disabled {
  opacity: 0.6;
  cursor: default;
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-body-md);
  margin-bottom: var(--space-md);
}

.banner-error {
  background: var(--color-error-container);
  color: var(--color-on-error-container);
}

.empty {
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-body-md);
  margin-top: var(--space-sm);
}
</style>
