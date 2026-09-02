<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const email = ref((route.query.email as string) ?? '')
const password = ref('')
const submitting = ref(false)
const justRegistered = route.query.registered === '1'

async function onSubmit(): Promise<void> {
  submitting.value = true
  try {
    await auth.login(email.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.push(redirect)
  } catch {
    // auth.errorMessage already holds a user-facing message; nothing else to do.
  } finally {
    submitting.value = false
    password.value = ''
  }
}
</script>

<template>
  <main class="auth-page">
    <div class="auth-card">
      <div class="brand-mark">
        <Icon name="lock" :size="26" />
        <h1>VeilKeeper</h1>
      </div>
      <p class="subtitle">Sign in to your vault</p>

      <p v-if="justRegistered" class="banner banner-success">
        Account created. Sign in with your new password to continue.
      </p>
      <p v-if="auth.errorMessage" class="banner banner-error" role="alert">
        {{ auth.errorMessage }}
      </p>

      <form @submit.prevent="onSubmit">
        <label for="email">Email</label>
        <input id="email" v-model="email" type="email" autocomplete="username" required />

        <label for="password">Password</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password" required />

        <button type="submit" :disabled="submitting">
          {{ submitting ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>

      <p class="footer-link">No account yet? <RouterLink to="/register">Register</RouterLink></p>
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
  font-size: var(--font-size-headline);
  color: var(--color-on-surface);
}

.subtitle {
  margin: var(--space-xs) 0 var(--space-lg);
  color: var(--color-on-surface-variant);
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

.banner-success {
  background: var(--color-success-container);
  color: var(--color-success);
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
</style>
