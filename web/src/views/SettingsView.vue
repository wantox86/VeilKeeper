<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useSettingsStore } from '../stores/settings'
import { useAuthStore } from '../stores/auth'
import { AUTO_LOCK_TIMEOUT_OPTIONS, CLIPBOARD_CLEAR_DELAY_OPTIONS } from '../types/lock'
import type { AutoLockTimeoutId, ClipboardClearDelayId } from '../types/lock'
import AppLayout from '../components/AppLayout.vue'
import Icon from '../components/Icon.vue'

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
  <AppLayout>
    <div class="settings-page">
      <h1>Settings</h1>

      <section class="section">
        <h2>Auto Lock</h2>
        <p class="hint">
          Locks the vault (clears the decryption key from memory) after this much inactivity, or as soon as
          this tab is hidden if set to Immediately.
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
          <button type="button" @click="onLockNow">
            <Icon name="lock" :size="16" />
            Lock now
          </button>
          <button type="button" class="danger" @click="onLogout">
            <Icon name="logout" :size="16" />
            Log out
          </button>
        </div>
      </section>
    </div>
  </AppLayout>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
  max-width: 32rem;
}

h1 {
  margin: 0;
  font-size: var(--font-size-title-lg);
}

.section {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

h2 {
  font-size: var(--font-size-title-md);
  margin: 0;
}

.hint {
  margin: 0 0 var(--space-sm);
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-body-md);
  line-height: 1.4;
}

.radio-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.radio-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--font-size-body-lg);
}

.button-row {
  display: flex;
  gap: var(--space-sm);
}

.button-row button {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.5rem 1rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  font-size: var(--font-size-label-lg);
  font-weight: 600;
}

.button-row .danger {
  border-color: var(--color-error);
  color: var(--color-error);
}
</style>
