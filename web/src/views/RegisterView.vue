<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Icon from '../components/Icon.vue'

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
      <div class="brand-mark">
        <Icon name="lock" :size="26" />
        <h1>Create your vault</h1>
      </div>
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
  background: var(--color-background);
  padding: var(--space-xl) var(--space-md);
}

.auth-card {
  width: 100%;
  max-width: 26rem;
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
  margin: var(--space-xs) 0 var(--space-md);
  color: var(--color-on-surface-variant);
}

.disclosure {
  background: var(--color-warning-container);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-md);
  padding: 0.75rem 0.9rem;
  font-size: var(--font-size-body-md);
  color: var(--color-warning);
  margin-bottom: var(--space-md);
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
</style>
