<script setup lang="ts">
/**
 * Shared loading state (Web Sprint 7 -- UI Polish, task 3), mirroring
 * Android's `VeilKeeperLoading` in `StateViews.kt`: a spinner plus an
 * optional label, instead of every view rendering its own bare "Loading…"
 * text. A plain CSS-animated ring -- no animation library, consistent with
 * this sprint's brief against flashy motion (Section 27 "subtle
 * animation").
 */
withDefaults(defineProps<{ label?: string; fullHeight?: boolean }>(), {
  label: 'Loading…',
  fullHeight: false,
})
</script>

<template>
  <div class="loading-state" :class="{ 'full-height': fullHeight }" role="status" :aria-label="label">
    <span class="spinner" aria-hidden="true" />
    <p v-if="label" class="label">{{ label }}</p>
  </div>
</template>

<style scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: var(--space-xl) var(--space-lg);
  color: var(--color-on-surface-variant);
}

.loading-state.full-height {
  min-height: 40vh;
}

.spinner {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 3px solid var(--color-surface-variant);
  border-top-color: var(--color-primary);
  animation: vk-spin 0.7s linear infinite;
}

.label {
  margin: 0;
  font-size: var(--font-size-body-md);
}

@keyframes vk-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 1.6s;
  }
}
</style>
