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
    <p class="subtitle">Sprint 1 scaffold -- backend connectivity check</p>

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
  margin: 4rem auto;
  padding: 0 1rem;
  font-family: system-ui, sans-serif;
}

.subtitle {
  color: #666;
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.5rem 1rem;
}

dt {
  font-weight: 600;
}

.status.ok {
  color: #1a7f37;
}

.status.error {
  color: #c1121f;
}

.status.checking {
  color: #9a6700;
}

button {
  margin-top: 1.5rem;
}
</style>
