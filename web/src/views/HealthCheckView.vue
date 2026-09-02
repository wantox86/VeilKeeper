<script setup lang="ts">
import { onMounted } from 'vue'
import { useHealthStore } from '../stores/health'
import { apiUrl } from '../services/api'

const health = useHealthStore()

onMounted(() => {
  health.check()
})
</script>

<template>
  <main class="health-check">
    <h1>VeilKeeper Web</h1>
    <p class="subtitle">Backend connectivity check</p>

    <dl>
      <dt>API base URL</dt>
      <dd>{{ apiUrl('') }}</dd>
      <dt>Status</dt>
      <dd :class="['status', health.status]">{{ health.status }}</dd>
      <dt>Backend response</dt>
      <dd>{{ health.message ?? '-' }}</dd>
    </dl>

    <button type="button" :disabled="health.status === 'checking'" @click="health.check()">Re-check</button>
  </main>
</template>

<style scoped>
.health-check {
  max-width: 32rem;
  margin: var(--space-xxl) auto;
  padding: 0 var(--space-md);
}

.subtitle {
  color: var(--color-on-surface-variant);
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-sm) var(--space-md);
}

dt {
  font-weight: 600;
}

.status.ok {
  color: var(--color-success);
}

.status.error {
  color: var(--color-error);
}

.status.checking {
  color: var(--color-warning);
}

button {
  margin-top: var(--space-lg);
  padding: 0.5rem 1rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
}

button:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
