<script setup lang="ts">
import { watch, onBeforeUnmount } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useSettingsStore } from './stores/settings'
import { createInactivityWatcher, type InactivityWatcher } from './services/idleTimer'

/**
 * Web Session Lock (SPEC-BASE.md Section 32) -- global wiring, one place
 * for the whole app, mirroring Android's single `VeilKeeperApplication`
 * setup of `AutoLockManager` rather than every screen managing its own
 * inactivity timer. The watcher only runs while a session is unlocked
 * (`lockState === 'unlocked'`) -- nothing to protect and nothing to lock
 * once already `locked` or `logged_out`.
 */
const auth = useAuthStore()
const settings = useSettingsStore()
const route = useRoute()
const router = useRouter()

let watcher: InactivityWatcher | null = null

function handleTimeout(): void {
  auth.lock()
}

/**
 * The router's `beforeEach` guard (see `router/index.ts`) only redirects to
 * `/locked` on the *next* navigation -- it does nothing while the user is
 * already sitting on a page when the idle timer fires. Without this, a
 * user who stops touching the mouse/keyboard on, say, `/items/42` would
 * have `auth.lockState` flip to `'locked'` in the background but the
 * decrypted vault item would stay rendered on screen indefinitely, since
 * nothing ever triggers a navigation. This mirrors Android's global
 * `LaunchedEffect(lockState)` in `MainActivity`'s `NavHost` (see CLAUDE.md
 * "Post-launch fixes batch 2") -- one central place that reacts to a
 * lock-state change by imperatively navigating, instead of relying only on
 * a route-transition guard.
 */
watch(
  () => auth.lockState,
  (state) => {
    if (state === 'unlocked') {
      if (!watcher) {
        watcher = createInactivityWatcher({ timeoutMs: settings.autoLockTimeoutMs, onLock: handleTimeout })
      }
      watcher.start()
    } else {
      watcher?.stop()
    }

    if (state === 'locked' && route.name !== 'locked') {
      router.push({ name: 'locked', query: { redirect: route.fullPath } })
    }
  },
  { immediate: true },
)

watch(
  () => settings.autoLockTimeoutMs,
  (ms) => {
    watcher?.setTimeoutMs(ms)
  },
)

onBeforeUnmount(() => {
  watcher?.stop()
})
</script>

<template>
  <RouterView />
</template>
