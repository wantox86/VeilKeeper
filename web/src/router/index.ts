import { createRouter, createWebHistory } from 'vue-router'
import HealthCheckView from '../views/HealthCheckView.vue'

/**
 * Sprint 1 scope: only the health-check page exists. Login/Register/Vault
 * routes land in Sprint 2+ (see CLAUDE.md "Web Sprint Roadmap").
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', name: 'health', component: HealthCheckView }],
})

export default router
