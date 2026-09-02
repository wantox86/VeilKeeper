<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Icon from '../components/Icon.vue'

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
      <div class="brand-mark">
        <Icon name="lock" :size="26" />
        <h1>Vault locked</h1>
      </div>
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
  background: var(--color-background);
  padding: var(--space-lg);
}

.auth-card {
  width: 100%;
  max-width: 24rem;
  padding: var(--space-xl);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-primary);
}

h1 {
  margin: 0;
  font-size: var(--font-size-title-lg);
  color: var(--color-on-surface);
}

.subtitle {
  margin: var(--space-xs) 0 var(--space-lg);
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-body-md);
}

form {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

label {
  font-size: var(--font-size-label-lg);
  font-weight: 600;
  margin-top: var(--space-sm);
}

input {
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  font-size: 1rem;
  background: var(--color-surface);
  color: var(--color-on-surface);
}

button {
  margin-top: var(--space-lg);
  padding: 0.65rem;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
}

button:disabled {
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

.footer-link {
  margin-top: var(--space-lg);
  font-size: var(--font-size-body-md);
  text-align: center;
}

.link-button {
  margin: 0;
  padding: 0;
  border: none;
  background: none;
  color: var(--color-primary);
  text-decoration: underline;
  font-size: inherit;
  cursor: pointer;
}
</style>
