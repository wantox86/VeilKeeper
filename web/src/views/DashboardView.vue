<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <main class="dashboard">
    <header>
      <h1>VeilKeeper</h1>
      <button type="button" class="logout" @click="onLogout">Log out</button>
    </header>

    <p class="welcome">
      Signed in as <strong>{{ auth.email }}</strong>
    </p>
    <p class="note">Vault CRUD UI lands in a later Web sprint -- Sprint 2 scope is authentication only.</p>
  </main>
</template>

<style scoped>
.dashboard {
  max-width: 40rem;
  margin: 3rem auto;
  padding: 0 1.5rem;
  font-family: system-ui, sans-serif;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

h1 {
  margin: 0;
  font-size: 1.5rem;
}

.logout {
  padding: 0.5rem 1rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
}

.welcome {
  margin-top: 1.5rem;
}

.note {
  color: #666;
  font-size: 0.9rem;
}
</style>
