import { createRouter, createWebHistory } from 'vue-router'
import HealthCheckView from '../views/HealthCheckView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import DashboardView from '../views/DashboardView.vue'
import CategoryView from '../views/CategoryView.vue'
import VaultItemView from '../views/VaultItemView.vue'
import VaultItemFormView from '../views/VaultItemFormView.vue'
import LockedView from '../views/LockedView.vue'
import SettingsView from '../views/SettingsView.vue'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    publicOnly?: boolean
  }
}

/**
 * Web Sprint 3 adds vault CRUD routes (categories, items) on top of Sprint
 * 2's auth routes + the Sprint 1 health-check page. `/dashboard` is now the
 * vault Home view (category list + recent items), not just a post-login
 * placeholder.
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', name: 'login', component: LoginView, meta: { publicOnly: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { publicOnly: true } },
    { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { requiresAuth: true } },
    { path: '/categories/:id', name: 'category', component: CategoryView, meta: { requiresAuth: true } },
    { path: '/items/new', name: 'item-new', component: VaultItemFormView, meta: { requiresAuth: true } },
    { path: '/items/:id', name: 'item', component: VaultItemView, meta: { requiresAuth: true } },
    {
      path: '/items/:id/edit',
      name: 'item-edit',
      component: VaultItemFormView,
      meta: { requiresAuth: true },
    },
    { path: '/settings', name: 'settings', component: SettingsView, meta: { requiresAuth: true } },
    { path: '/locked', name: 'locked', component: LockedView },
    { path: '/health', name: 'health', component: HealthCheckView },
  ],
})

/**
 * Web Sprint 4 adds a third state on top of Sprint 2's plain
 * authenticated/not-authenticated split: `auth.lockState` is
 * `'logged_out' | 'locked' | 'unlocked'` (see `stores/auth.ts`). Guard
 * order matters here:
 *
 *  1. A locked session takes priority over every other rule (except the
 *     `/locked` route itself and `/health`, which stays reachable
 *     unauthenticated for diagnostics same as before) -- redirects there
 *     instead of letting a locked user reach `/login` (would spuriously
 *     start a second session) or any vault route (would show stale UI with
 *     no VDK to decrypt anything).
 *  2. Visiting `/locked` without an actual locked session redirects onward
 *     (to `/dashboard` if unlocked, `/login` if never logged in at all) --
 *     `/locked` is a state-reflecting route, not a page you can just park
 *     on.
 *  3. Sprint 2's original two rules, unchanged.
 */
router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.name !== 'locked' && to.name !== 'health' && auth.isLocked) {
    return { name: 'locked', query: to.meta.requiresAuth ? { redirect: to.fullPath } : undefined }
  }

  if (to.name === 'locked' && !auth.isLocked) {
    return auth.isAuthenticated ? { name: 'dashboard' } : { name: 'login' }
  }

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.publicOnly && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }

  return true
})

export default router
