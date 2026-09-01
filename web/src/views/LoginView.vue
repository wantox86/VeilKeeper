<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

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
      <h1>VeilKeeper</h1>
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

.banner-success {
  background: #ecfdf3;
  color: #1a7f37;
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
</style>
