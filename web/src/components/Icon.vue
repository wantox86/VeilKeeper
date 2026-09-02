<script setup lang="ts">
import { computed } from 'vue'

/**
 * Tiny local icon set (Web Sprint 7 -- UI Polish, task 7 "consistent
 * iconography"). Before this sprint the app used zero icons (every action
 * was a text-label button) -- rather than pull in an icon library (a new
 * dependency for what SPEC-BASE.md Section 56 Rule 1 would call
 * overengineering at this scope), a handful of hand-authored outline SVGs
 * are defined here, all sharing one visual language: 24x24 viewBox,
 * `currentColor` stroke, 1.75 stroke-width, round caps/joins -- the same
 * "outlined" family Android's Material icons (`Icons.Filled.*` used in
 * `StateViews.kt`) read as, so both clients feel like one visual system.
 *
 * Used sparingly and consistently: empty/error states, a few icon-only
 * controls (reveal/hide, copy, delete, add), and the brand mark -- never
 * mixed with emoji, matching this sprint's brief against inconsistent
 * icon/emoji/text mixing.
 */
const props = defineProps<{
  name:
    | 'empty'
    | 'error'
    | 'lock'
    | 'search'
    | 'eye'
    | 'eye-off'
    | 'copy'
    | 'trash'
    | 'plus'
    | 'image'
    | 'settings'
    | 'logout'
    | 'chevron-left'
    | 'check'
  size?: number
}>()

const size = computed(() => props.size ?? 20)

const paths: Record<string, string> = {
  empty: 'M3 7.5 12 3l9 4.5M3 7.5v9L12 21m-9-4.5 9 4.5m0-13.5v13.5m0-13.5 9 4.5m-9 9 9-4.5v-9',
  error:
    'M12 9v4m0 4h.01M10.29 3.86l-8.18 14.18A2 2 0 0 0 3.82 21h16.36a2 2 0 0 0 1.71-2.96L13.71 3.86a2 2 0 0 0-3.42 0Z',
  lock: 'M6 10V8a6 6 0 1 1 12 0v2m-13 0h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1Z',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm9 2-4.35-4.35',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  'eye-off':
    'M3 3l18 18M10.6 5.1A10.6 10.6 0 0 1 12 5c6.5 0 10 7 10 7a17.7 17.7 0 0 1-3.15 4.05M6.5 6.7C4 8.3 2 12 2 12s3.5 7 10 7c1.36 0 2.59-.3 3.66-.79M9.9 9.9a3 3 0 0 0 4.2 4.2',
  copy: 'M9 9V4.6c0-.88.72-1.6 1.6-1.6h8.8c.88 0 1.6.72 1.6 1.6v8.8c0 .88-.72 1.6-1.6 1.6H15M4.6 9h8.8c.88 0 1.6.72 1.6 1.6v8.8c0 .88-.72 1.6-1.6 1.6H4.6A1.6 1.6 0 0 1 3 19.4v-8.8C3 9.72 3.72 9 4.6 9Z',
  trash:
    'M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2m3 0-.7 12.1a2 2 0 0 1-2 1.9H8.7a2 2 0 0 1-2-1.9L6 7h12Z',
  plus: 'M12 5v14M5 12h14',
  image:
    'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Zm2 12 5-6 3 3.5L17 10l3 5.5M9 9.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z',
  settings:
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm8-3a8 8 0 0 0-.14-1.5l2.02-1.58-2-3.46-2.38.96a8 8 0 0 0-2.6-1.5L14.5 2h-5l-.4 2.92a8 8 0 0 0-2.6 1.5l-2.38-.96-2 3.46L4.14 10.5a8 8 0 0 0 0 3l-2.02 1.58 2 3.46 2.38-.96a8 8 0 0 0 2.6 1.5L9.5 22h5l.4-2.92a8 8 0 0 0 2.6-1.5l2.38.96 2-3.46-2.02-1.58c.09-.49.14-.99.14-1.5Z',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4m6 14 5-5-5-5m5 5H9',
  'chevron-left': 'M15 18l-6-6 6-6',
  check: 'M20 6 9 17l-5-5',
}
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.75"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
    class="vk-icon"
  >
    <path :d="paths[name]" />
  </svg>
</template>

<style scoped>
.vk-icon {
  flex-shrink: 0;
  display: block;
}
</style>
