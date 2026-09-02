<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useVaultStore } from '../stores/vault'
import { filterItems } from '../services/vaultSearch'
import AppLayout from '../components/AppLayout.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import Icon from '../components/Icon.vue'

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
const vault = useVaultStore()

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
</script>

<template>
  <AppLayout>
    <div class="home">
      <p v-if="vault.errorMessage" class="banner banner-error" role="alert">{{ vault.errorMessage }}</p>

      <div class="search-bar">
        <Icon name="search" :size="18" class="search-icon" />
        <input
          v-model="searchQuery"
          type="search"
          placeholder="Search title, labels, notes…"
          aria-label="Search vault items"
        />
      </div>

      <LoadingState v-if="loading" full-height />

      <section v-else-if="isSearching" class="section">
        <h2>Search results</h2>
        <ul v-if="searchResults.length" class="item-list">
          <li v-for="item in searchResults" :key="item.id">
            <RouterLink :to="`/items/${item.id}`" class="item-row">
              <span class="item-title">{{ item.payload.title }}</span>
              <span class="item-category">{{ categoryName(item.categoryId) }}</span>
            </RouterLink>
          </li>
        </ul>
        <EmptyState v-else title="No matching items" message="Try a different title, label, or note text." />
      </section>

      <template v-else>
        <section class="section">
          <div class="section-header">
            <h2>Categories</h2>
            <RouterLink to="/items/new" class="button-link">
              <Icon name="plus" :size="16" />
              New item
            </RouterLink>
          </div>

          <form class="new-category-form" @submit.prevent="onCreateCategory">
            <input v-model="newCategoryName" type="text" placeholder="New category name" maxlength="100" />
            <button type="submit" :disabled="creatingCategory || !newCategoryName.trim()">Add</button>
          </form>

          <ul v-if="vault.categories.length" class="category-list">
            <li v-for="category in vault.categories" :key="category.id">
              <RouterLink :to="`/categories/${category.id}`" class="category-card">
                <span class="category-name">{{ category.name }}</span>
                <span class="category-count">{{ category.item_count }} item(s)</span>
              </RouterLink>
            </li>
          </ul>
          <EmptyState
            v-else
            title="No categories yet"
            message="Categories help you organize your vault -- add your first one above."
          />
        </section>

        <section class="section">
          <h2>Recent items</h2>
          <ul v-if="recentItems.length" class="item-list">
            <li v-for="item in recentItems" :key="item.id">
              <RouterLink :to="`/items/${item.id}`" class="item-row">
                <span class="item-title">{{ item.payload.title }}</span>
                <span class="item-category">{{ categoryName(item.categoryId) }}</span>
              </RouterLink>
            </li>
          </ul>
          <EmptyState
            v-else
            title="No vault items yet"
            message="Create your first secret, note, or credential."
            action-label="+ New item"
            @action="$router.push('/items/new')"
          />
        </section>
      </template>
    </div>
  </AppLayout>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.search-bar {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 0.75rem;
  color: var(--color-on-surface-variant);
  pointer-events: none;
}

.search-bar input {
  width: 100%;
  padding: 0.6rem 0.9rem 0.6rem 2.5rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  font-size: var(--font-size-body-lg);
  background: var(--color-surface);
  color: var(--color-on-surface);
  box-sizing: border-box;
}

.section {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

h2 {
  font-size: var(--font-size-title-md);
  margin: 0;
}

.button-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 0.4rem 0.8rem;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  text-decoration: none;
  font-size: var(--font-size-label-lg);
  font-weight: 600;
}

.new-category-form {
  display: flex;
  gap: var(--space-sm);
}

.new-category-form input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-on-surface);
}

.new-category-form button {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-weight: 600;
  cursor: pointer;
}

.new-category-form button:disabled {
  opacity: 0.6;
  cursor: default;
}

.category-list,
.item-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.category-card,
.item-row {
  display: flex;
  justify-content: space-between;
  padding: 0.65rem 0.9rem;
  border: 1px solid var(--color-surface-variant);
  border-radius: var(--radius-md);
  text-decoration: none;
  color: inherit;
  background: var(--color-surface);
}

.category-card:hover,
.item-row:hover {
  border-color: var(--color-primary);
}

.category-count,
.item-category {
  color: var(--color-on-surface-variant);
  font-size: var(--font-size-body-md);
}

.banner {
  padding: 0.6rem 0.75rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-body-md);
}

.banner-error {
  background: var(--color-error-container);
  color: var(--color-on-error-container);
}
</style>
