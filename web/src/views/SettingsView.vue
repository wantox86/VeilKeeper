<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useSettingsStore } from '../stores/settings'
import { useAuthStore } from '../stores/auth'
import { AUTO_LOCK_TIMEOUT_OPTIONS, CLIPBOARD_CLEAR_DELAY_OPTIONS } from '../types/lock'
import type { AutoLockTimeoutId, ClipboardClearDelayId } from '../types/lock'

/**
 * Settings screen (task item 5) -- mirrors Android's Settings screen scope
 * (auto-lock timeout + clipboard clear delay + a way to lock/log out), not
 * its exact layout. Deliberately minimal, same restraint Android's own
 * `SettingsScreen.kt` doc comment calls out (SPEC-BASE.md Section 56 Rule
 * 1) -- no theme/profile settings, no biometric toggle (no Web equivalent).
 */
const settings = useSettingsStore()
const auth = useAuthStore()
const router = useRouter()

function onAutoLockChange(id: AutoLockTimeoutId): void {
  settings.setAutoLockTimeout(id)
}

function onClipboardDelayChange(id: ClipboardClearDelayId): void {
  settings.setClipboardClearDelay(id)
}

async function onLockNow(): Promise<void> {
  auth.lock()
  await router.push('/locked')
}

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <main class="settings-page">
    <RouterLink to="/dashboard" class="back-link">&larr; Home</RouterLink>
    <h1>Settings</h1>

    <section class="section">
      <h2>Auto Lock</h2>
      <p class="hint">
        Locks the vault (clears the decryption key from memory) after this much inactivity, or as soon as this
        tab is hidden if set to Immediately.
      </p>
      <div class="radio-group" role="radiogroup" aria-label="Auto Lock timeout">
        <label v-for="option in AUTO_LOCK_TIMEOUT_OPTIONS" :key="option.id" class="radio-row">
          <input
            type="radio"
            name="auto-lock"
            :value="option.id"
            :checked="settings.autoLockTimeoutId === option.id"
            @change="onAutoLockChange(option.id)"
          />
          {{ option.label }}
        </label>
      </div>
    </section>

    <section class="section">
      <h2>Clipboard auto-clear</h2>
      <p class="hint">
        After copying a value, the clipboard is cleared automatically after this delay --
        <strong>but only if this tab is still open and focused when the timer runs out</strong>. Browsers
        block programmatic clipboard writes once the tab loses focus, so if you switch away right after
        copying, the value may stay on your clipboard until you overwrite it yourself. This is a real
        limitation of the browser Clipboard API, not a bug.
      </p>
      <div class="radio-group" role="radiogroup" aria-label="Clipboard auto-clear delay">
        <label v-for="option in CLIPBOARD_CLEAR_DELAY_OPTIONS" :key="option.id" class="radio-row">
          <input
            type="radio"
            name="clipboard-delay"
            :value="option.id"
            :checked="settings.clipboardClearDelayId === option.id"
            @change="onClipboardDelayChange(option.id)"
          />
          {{ option.label }}
        </label>
      </div>
    </section>

    <section class="section">
      <h2>Session</h2>
      <div class="button-row">
        <button type="button" @click="onLockNow">Lock now</button>
        <button type="button" class="danger" @click="onLogout">Log out</button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.settings-page {
  max-width: 32rem;
  margin: 3rem auto;
  padding: 0 1.5rem 3rem;
  font-family: system-ui, sans-serif;
}

.back-link {
  color: #3730a3;
  text-decoration: none;
  font-size: 0.9rem;
}

h1 {
  margin: 1rem 0 0;
  font-size: 1.4rem;
}

.section {
  margin-top: 2rem;
}

h2 {
  font-size: 1.05rem;
  margin: 0 0 0.35rem;
}

.hint {
  margin: 0 0 0.75rem;
  color: #555;
  font-size: 0.85rem;
  line-height: 1.4;
}

.radio-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.radio-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.95rem;
}

.button-row {
  display: flex;
  gap: 0.5rem;
}

.button-row button {
  padding: 0.5rem 1rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
}

.button-row .danger {
  border-color: #f4b8b8;
  color: #c1121f;
}
</style>
