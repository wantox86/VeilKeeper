<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useVaultStore } from '../stores/vault'
import { filterItems } from '../services/vaultSearch'

/**
 * Home view (Web Sprint 3) -- mirrors Android's Home screen scope
 * (categories + item counts + recent items). Kept as the `/dashboard` route
 * from Sprint 2 rather than adding a separate route, since this IS the
 * authenticated landing page, just with real content now.
 *
 * Web Sprint 5 adds a global search bar here rather than a separate search
 * view -- mirrors Android Sprint 4's own choice to put search directly on
 * Home (`ui/home/HomeScreen.kt`/`HomeViewModel`) instead of a dedicated
 * screen, and this view already fetches every vault item via
 * `vault.fetchItems()` below (no categoryId = all items across all
 * categories), so search has nothing new to fetch -- it's a pure in-memory
 * filter over `vault.items`, which is already the full decrypted list this
 * view was fetching anyway.
 */
const auth = useAuthStore()
const vault = useVaultStore()
const router = useRouter()

const loading = ref(true)
const newCategoryName = ref('')
const creatingCategory = ref(false)
const searchQuery = ref('')

const isSearching = computed(() => searchQuery.value.trim().length > 0)

/** Pure client-side filter (see `services/vaultSearch.ts`) -- never triggers a fetch or network call. */
const searchResults = computed(() => filterItems(vault.items, searchQuery.value))

const recentItems = computed(() =>
  [...vault.items].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 5),
)

function categoryName(categoryId: number): string {
  return vault.categories.find((c) => c.id === categoryId)?.name ?? 'Unknown'
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    await vault.fetchCategories()
    await vault.fetchItems()
  } catch {
    // vault.errorMessage already holds a user-facing message (rendered in
    // the banner below) -- swallow here so a mid-load session change (e.g.
    // logging out while this is still in flight) never surfaces as an
    // unhandled promise rejection.
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

async function onCreateCategory(): Promise<void> {
  const name = newCategoryName.value.trim()
  if (!name) return
  creatingCategory.value = true
  try {
    await vault.createCategory(name)
    newCategoryName.value = ''
  } catch {
    // vault.errorMessage already holds a user-facing message.
  } finally {
    creatingCategory.value = false
  }
}

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <main class="home">
    <header>
      <h1>VeilKeeper</h1>
      <div class="header-actions">
        <span class="welcome"
          >Signed in as <strong>{{ auth.email }}</strong></span
        >
        <RouterLink to="/settings" class="button-link">Settings</RouterLink>
        <button type="button" class="logout" @click="onLogout">Log out</button>
      </div>
    </header>

    <p v-if="vault.errorMessage" class="banner banner-error" role="alert">{{ vault.errorMessage }}</p>

    <div class="search-bar">
      <input
        v-model="searchQuery"
        type="search"
        placeholder="Search title, labels, notes…"
        aria-label="Search vault items"
      />
    </div>

    <section v-if="isSearching" class="section">
      <h2>Search results</h2>
      <p v-if="loading">Loading…</p>
      <ul v-else class="item-list">
        <li v-for="item in searchResults" :key="item.id">
          <RouterLink :to="`/items/${item.id}`" class="item-row">
            <span class="item-title">{{ item.payload.title }}</span>
            <span class="item-category">{{ categoryName(item.categoryId) }}</span>
          </RouterLink>
        </li>
        <li v-if="!searchResults.length" class="empty">No matching items.</li>
      </ul>
    </section>

    <template v-else>
      <section class="section">
        <div class="section-header">
          <h2>Categories</h2>
          <RouterLink to="/items/new" class="button-link">+ New item</RouterLink>
        </div>

        <form class="new-category-form" @submit.prevent="onCreateCategory">
          <input v-model="newCategoryName" type="text" placeholder="New category name" maxlength="100" />
          <button type="submit" :disabled="creatingCategory || !newCategoryName.trim()">Add</button>
        </form>

        <p v-if="loading">Loading…</p>
        <ul v-else class="category-list">
          <li v-for="category in vault.categories" :key="category.id">
            <RouterLink :to="`/categories/${category.id}`" class="category-card">
              <span class="category-name">{{ category.name }}</span>
              <span class="category-count">{{ category.item_count }} item(s)</span>
            </RouterLink>
          </li>
          <li v-if="!vault.categories.length" class="empty">No categories yet.</li>
        </ul>
      </section>

      <section class="section">
        <h2>Recent items</h2>
        <p v-if="loading">Loading…</p>
        <ul v-else class="item-list">
          <li v-for="item in recentItems" :key="item.id">
            <RouterLink :to="`/items/${item.id}`" class="item-row">
              <span class="item-title">{{ item.payload.title }}</span>
              <span class="item-category">{{ categoryName(item.categoryId) }}</span>
            </RouterLink>
          </li>
          <li v-if="!recentItems.length" class="empty">No vault items yet.</li>
        </ul>
      </section>
    </template>
  </main>
</template>

<style scoped>
.home {
  max-width: 40rem;
  margin: 3rem auto;
  padding: 0 1.5rem 3rem;
  font-family: system-ui, sans-serif;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

h1 {
  margin: 0;
  font-size: 1.5rem;
}

.welcome {
  font-size: 0.9rem;
  color: #444;
}

.logout {
  padding: 0.5rem 1rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
}

.search-bar {
  margin-top: 1.25rem;
}

.search-bar input {
  width: 100%;
  padding: 0.6rem 0.9rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
  font-size: 0.95rem;
  box-sizing: border-box;
}

.section {
  margin-top: 2rem;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

h2 {
  font-size: 1.1rem;
  margin: 0;
}

.button-link {
  padding: 0.4rem 0.8rem;
  border-radius: 0.5rem;
  background: #3730a3;
  color: white;
  text-decoration: none;
  font-size: 0.85rem;
}

.new-category-form {
  display: flex;
  gap: 0.5rem;
  margin: 0.75rem 0;
}

.new-category-form input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d0d5dd;
  border-radius: 0.5rem;
}

.new-category-form button {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 0.5rem;
  background: #3730a3;
  color: white;
  cursor: pointer;
}

.new-category-form button:disabled {
  opacity: 0.6;
  cursor: default;
}

.category-list,
.item-list {
  list-style: none;
  margin: 0.75rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.category-card,
.item-row {
  display: flex;
  justify-content: space-between;
  padding: 0.65rem 0.9rem;
  border: 1px solid #e4e7ec;
  border-radius: 0.5rem;
  text-decoration: none;
  color: inherit;
  background: white;
}

.category-count,
.item-category {
  color: #666;
  font-size: 0.85rem;
}

.empty {
  color: #888;
  font-size: 0.9rem;
  padding: 0.5rem 0;
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.9rem;
  margin-top: 1rem;
}

.banner-error {
  background: #fef3f2;
  color: #c1121f;
}
</style>
