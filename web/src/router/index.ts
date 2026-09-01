import { createRouter, createWebHistory } from 'vue-router'
import HealthCheckView from '../views/HealthCheckView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import DashboardView from '../views/DashboardView.vue'
import CategoryView from '../views/CategoryView.vue'
import VaultItemView from '../views/VaultItemView.vue'
import VaultItemFormView from '../views/VaultItemFormView.vue'
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
    { path: '/health', name: 'health', component: HealthCheckView },
  ],
})

// Protected-route guard: redirect to /login (preserving the intended
// destination) if there's no active session. Also redirect an already
// logged-in user away from /login and /register, since re-registering or
// re-deriving a login while a session is active isn't a meaningful action.
router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.publicOnly && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }

  return true
})

export default router
