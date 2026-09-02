<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

/**
 * Web Session Lock unlock screen (SPEC-BASE.md Section 32) -- the Web
 * analogue of Android's Unlock screen, minus biometric (no Web equivalent).
 * Reached via the router guard whenever `auth.lockState === 'locked'`
 * (idle timeout, tab backgrounded past the configured delay, or a manual
 * "Lock now" from Settings). Unlocking re-derives the VDK from the
 * password + the non-secret `unwrapMaterial` already held in the auth
 * store -- no network call, and it's the exact same vault, not a fresh
 * login (see `stores/auth.ts`'s `unlockWithPassword` doc comment).
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const password = ref('')
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  submitting.value = true
  try {
    await auth.unlockWithPassword(password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.push(redirect)
  } catch {
    // auth.errorMessage already holds "Incorrect password."
  } finally {
    submitting.value = false
    password.value = ''
  }
}

async function onLogoutInstead(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <main class="auth-page">
    <div class="auth-card">
      <h1>Vault locked</h1>
      <p class="subtitle">
        Signed in as <strong>{{ auth.email }}</strong
        >. Enter your password to continue.
      </p>

      <p v-if="auth.errorMessage" class="banner banner-error" role="alert">
        {{ auth.errorMessage }}
      </p>

      <form @submit.prevent="onSubmit">
        <label for="password">Password</label>
        <input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          autofocus
          required
        />

        <button type="submit" :disabled="submitting || !password">
          {{ submitting ? 'Unlocking…' : 'Unlock' }}
        </button>
      </form>

      <p class="footer-link">
        Not you? <button type="button" class="link-button" @click="onLogoutInstead">Log out instead</button>
      </p>
    </div>
  </main>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f6f8;
  font-family: system-ui, sans-serif;
}

.auth-card {
  width: 100%;
  max-width: 24rem;
  padding: 2rem;
  border-radius: 0.75rem;
  background: white;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

h1 {
  margin: 0 0 0.25rem;
  font-size: 1.5rem;
}

.subtitle {
  margin: 0 0 1.5rem;
  color: #666;
  font-size: 0.9rem;
}

form {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

label {
  font-size: 0.85rem;
  font-weight: 600;
  margin-top: 0.75rem;
}

input {
  padding: 0.6rem 0.75rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  font-size: 1rem;
}

button {
  margin-top: 1.5rem;
  padding: 0.65rem;
  border: none;
  border-radius: 0.5rem;
  background: #3730a3;
  color: white;
  font-size: 1rem;
  cursor: pointer;
}

button:disabled {
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

.footer-link {
  margin-top: 1.5rem;
  font-size: 0.9rem;
  text-align: center;
}

.link-button {
  margin: 0;
  padding: 0;
  border: none;
  background: none;
  color: #3730a3;
  text-decoration: underline;
  font-size: inherit;
  cursor: pointer;
}
</style>
