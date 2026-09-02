<script setup lang="ts">
import Icon from './Icon.vue'

/**
 * Shared error state (Web Sprint 7 -- UI Polish, task 3), mirroring
 * Android's `VeilKeeperErrorState` in `StateViews.kt`: an icon + message +
 * optional retry action, replacing the ad hoc `.banner-error` paragraphs
 * used for *inline* validation/action errors elsewhere in this app. This
 * component is specifically for "the whole view failed to load" states
 * (e.g. `loadError` in `VaultItemView.vue`, `CategoryView.vue`'s "not
 * found") -- inline form/action errors still use the lighter `.banner`
 * treatment (a full centered icon block would be wrong for "title is
 * required" under a text field).
 */
withDefaults(defineProps<{ message: string; retryLabel?: string; showRetry?: boolean }>(), {
  retryLabel: 'Retry',
  showRetry: false,
})

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="error-state" role="alert">
    <Icon name="error" :size="32" />
    <p class="message">{{ message }}</p>
    <button v-if="showRetry" type="button" class="retry" @click="emit('retry')">
      {{ retryLabel }}
    </button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--space-xs);
  padding: var(--space-xl) var(--space-lg);
  color: var(--color-error);
}

.message {
  margin: var(--space-xs) 0 0;
  font-size: var(--font-size-body-md);
  max-width: 26rem;
}

.retry {
  margin-top: var(--space-sm);
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.45rem 0.9rem;
  border: 1px solid var(--color-error);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-error);
  font-size: var(--font-size-label-lg);
  font-weight: 600;
  cursor: pointer;
}
</style>
