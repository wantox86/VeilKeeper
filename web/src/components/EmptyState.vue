<script setup lang="ts">
import Icon from './Icon.vue'

/**
 * Shared "nothing here yet" state (Web Sprint 7 -- UI Polish, task 3),
 * mirroring Android's `VeilKeeperEmptyState` in `StateViews.kt` field-for-
 * field (icon + title + optional message + optional action), so every view
 * that can be empty (no categories, no items, no search results, no
 * attachments) looks and reads the same instead of each screen inventing
 * its own "No X yet." line of muted text.
 */
withDefaults(
  defineProps<{
    title: string
    message?: string
    actionLabel?: string
  }>(),
  { message: undefined, actionLabel: undefined },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="empty-state">
    <Icon name="empty" :size="32" />
    <p class="title">{{ title }}</p>
    <p v-if="message" class="message">{{ message }}</p>
    <button v-if="actionLabel" type="button" class="action" @click="emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--space-xs);
  padding: var(--space-xl) var(--space-lg);
  color: var(--color-on-surface-variant);
}

.title {
  margin: var(--space-xs) 0 0;
  font-size: var(--font-size-title-md);
  font-weight: 600;
  color: var(--color-on-surface);
}

.message {
  margin: 0;
  font-size: var(--font-size-body-md);
  max-width: 26rem;
}

.action {
  margin-top: var(--space-sm);
  padding: 0.45rem 0.9rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: var(--font-size-label-lg);
  font-weight: 600;
  cursor: pointer;
}

.action:hover {
  background: var(--color-primary-container);
}
</style>
