<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useVaultStore } from '../stores/vault'
import Icon from './Icon.vue'

/**
 * Shared authenticated-app shell (Web Sprint 7 -- UI Polish, task 4
 * "Responsive layout"). Mirrors the sidebar + topbar layout SPEC-BASE.md
 * Section 25 "Web Layout" sketches (brand/search/user topbar, a left nav of
 * categories) -- a conceptual reference, not pixel-perfect, per that
 * section's own wording.
 *
 * Used by every authenticated view (Dashboard, Category, VaultItem detail,
 * VaultItemForm, Settings) so navigation (Home + category list + Settings +
 * Logout) is available everywhere instead of each view's own ad hoc
 * "&larr; Home" text link -- one place for the responsive breakpoint too.
 *
 * Responsive behavior (desktop/tablet primary, mobile "usable" per Section
 * 24): >=900px shows a persistent left sidebar; below that the sidebar
 * collapses to a horizontal scrollable chip row above the content, so
 * mobile never needs a hamburger/drawer (extra state) to reach navigation.
 *
 * Deliberately does NOT own the global search box -- that stays on
 * `DashboardView.vue`, which already holds the full decrypted item list
 * this sprint's brief's "search" reference is about; duplicating that
 * fetch/filter state here for a topbar-level search input was judged more
 * machinery than this sprint's scope (visual/UX polish, not new features)
 * justifies. A `search` icon-only link here jumps to `/dashboard` where the
 * real search box lives.
 */
const auth = useAuthStore()
const vault = useVaultStore()
const router = useRouter()

onMounted(() => {
  if (!vault.categories.length) {
    void vault.fetchCategories()
  }
})

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink to="/dashboard" class="brand">
        <Icon name="lock" :size="20" />
        <span>VeilKeeper</span>
      </RouterLink>

      <nav class="topbar-actions" aria-label="Account">
        <RouterLink to="/dashboard" class="icon-btn" aria-label="Search vault">
          <Icon name="search" :size="18" />
        </RouterLink>
        <span class="welcome">{{ auth.email }}</span>
        <RouterLink to="/settings" class="icon-btn" aria-label="Settings">
          <Icon name="settings" :size="18" />
        </RouterLink>
        <button type="button" class="icon-btn" aria-label="Log out" @click="onLogout">
          <Icon name="logout" :size="18" />
        </button>
      </nav>
    </header>

    <div class="body">
      <nav class="sidebar" aria-label="Categories">
        <RouterLink
          to="/dashboard"
          class="nav-item"
          active-class="nav-item-active"
          exact-active-class="nav-item-active"
        >
          Home
        </RouterLink>
        <p class="nav-heading">Categories</p>
        <RouterLink
          v-for="category in vault.categories"
          :key="category.id"
          :to="`/categories/${category.id}`"
          class="nav-item"
          active-class="nav-item-active"
        >
          {{ category.name }}
          <span class="nav-count">{{ category.item_count }}</span>
        </RouterLink>
      </nav>

      <main class="content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-sm) var(--space-lg);
  border-bottom: 1px solid var(--color-surface-variant);
  background: var(--color-surface);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-on-surface);
  text-decoration: none;
  font-size: var(--font-size-title-md);
  font-weight: 700;
}

.brand :deep(.vk-icon) {
  color: var(--color-primary);
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.welcome {
  font-size: var(--font-size-body-md);
  color: var(--color-on-surface-variant);
  max-width: 14rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.25rem;
  height: 2.25rem;
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  text-decoration: none;
}

.icon-btn:hover {
  background: var(--color-surface-container);
}

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.sidebar {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-lg);
  overflow-x: auto;
  border-bottom: 1px solid var(--color-surface-variant);
  background: var(--color-background);
}

.nav-heading {
  display: none;
}

.nav-item {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.4rem 0.75rem;
  border-radius: var(--radius-md);
  color: var(--color-on-surface-variant);
  text-decoration: none;
  font-size: var(--font-size-label-lg);
  font-weight: 500;
  white-space: nowrap;
}

.nav-item:hover {
  background: var(--color-surface-container);
}

.nav-item-active {
  background: var(--color-primary-container);
  color: var(--color-on-primary-container);
}

.nav-count {
  font-size: var(--font-size-label-md);
  color: inherit;
  opacity: 0.75;
}

.content {
  flex: 1;
  padding: var(--space-lg);
  max-width: 56rem;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}

@media (min-width: 900px) {
  .body {
    flex-direction: row;
  }

  .sidebar {
    flex-direction: column;
    align-items: stretch;
    width: 15rem;
    flex-shrink: 0;
    border-bottom: none;
    border-right: 1px solid var(--color-surface-variant);
    overflow-x: visible;
    padding: var(--space-lg) var(--space-md);
    gap: var(--space-xs);
  }

  .nav-heading {
    display: block;
    margin: var(--space-md) 0 var(--space-xs) 0.75rem;
    font-size: var(--font-size-label-md);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--color-on-surface-variant);
  }

  .nav-item {
    justify-content: space-between;
  }

  .content {
    padding: var(--space-xl) var(--space-xxl);
    max-width: 60rem;
    margin: 0;
  }
}
</style>
