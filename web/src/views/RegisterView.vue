<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const email = ref('')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const validationError = ref<string | null>(null)

const passwordsMatch = computed(() => password.value === confirmPassword.value)

async function onSubmit(): Promise<void> {
  validationError.value = null

  if (password.value.length < 8) {
    validationError.value = 'Password must be at least 8 characters.'
    return
  }
  if (!passwordsMatch.value) {
    validationError.value = 'Passwords do not match.'
    return
  }

  submitting.value = true
  try {
    await auth.register(email.value.trim(), username.value.trim(), password.value)
    await router.push({ name: 'login', query: { registered: '1', email: email.value.trim() } })
  } catch {
    // auth.errorMessage already holds a user-facing message; nothing else to do.
  } finally {
    submitting.value = false
    password.value = ''
    confirmPassword.value = ''
  }
}
</script>

<template>
  <main class="auth-page">
    <div class="auth-card">
      <h1>Create your vault</h1>
      <p class="subtitle">VeilKeeper is zero-knowledge -- we never see your password.</p>

      <p class="disclosure" role="note">
        <strong>If you forget your password, your vault cannot be recovered.</strong>
        There is no backdoor, by design -- nobody, not even the server operator, can reset it.
      </p>

      <p v-if="validationError" class="banner banner-error" role="alert">{{ validationError }}</p>
      <p v-if="auth.errorMessage" class="banner banner-error" role="alert">{{ auth.errorMessage }}</p>

      <form @submit.prevent="onSubmit">
        <label for="email">Email</label>
        <input id="email" v-model="email" type="email" autocomplete="username" required />

        <label for="username">Display name</label>
        <input id="username" v-model="username" type="text" autocomplete="nickname" required />

        <label for="password">Password</label>
        <input
          id="password"
          v-model="password"
          type="password"
          autocomplete="new-password"
          required
          minlength="8"
        />

        <label for="confirmPassword">Confirm password</label>
        <input
          id="confirmPassword"
          v-model="confirmPassword"
          type="password"
          autocomplete="new-password"
          required
          minlength="8"
        />

        <button type="submit" :disabled="submitting">
          {{ submitting ? 'Creating account…' : 'Create account' }}
        </button>
      </form>

      <p class="footer-link">Already have an account? <RouterLink to="/login">Sign in</RouterLink></p>
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
  padding: 2rem 1rem;
}

.auth-card {
  width: 100%;
  max-width: 26rem;
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
  margin: 0 0 1.25rem;
  color: #666;
}

.disclosure {
  background: #fffaeb;
  border: 1px solid #f0c36d;
  border-radius: 0.5rem;
  padding: 0.75rem 0.9rem;
  font-size: 0.85rem;
  color: #664d03;
  margin-bottom: 1.25rem;
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
</style>
