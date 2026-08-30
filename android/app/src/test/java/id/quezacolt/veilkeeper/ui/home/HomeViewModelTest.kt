package id.quezacolt.veilkeeper.ui.home

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeVaultApi
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.ui.auth.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeVaultApi
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        api = FakeVaultApi()
        repository = VaultRepository(api, ioDispatcher = mainDispatcherRule.testDispatcher, computeDispatcher = mainDispatcherRule.testDispatcher)
        AuthSessionHolder.set("token", ByteArray(32).also(SecureRandom()::nextBytes))
    }

    @After
    fun tearDown() {
        AuthSessionHolder.clear()
    }

    @Test
    fun `loads categories and recent items on init`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        repository.createItem(cat.id, "Item A", listOf(ContentBlockDto(type = "note", value = "n"))).getOrThrow()

        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.categories.size)
        assertEquals(1, state.recentItems.size)
        assertEquals("Item A", state.recentItems.first().title)
    }

    @Test
    fun `surfaces an error message when categories fail to load`() = runTest(mainDispatcherRule.testDispatcher) {
        api.forcedErrorCode = 500
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        org.junit.Assert.assertTrue(state.errorMessage != null)
    }

    @Test
    fun `global search filters across categories by title, label, and note content without any extra network call`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val work = repository.createCategory("Work").getOrThrow()
            val personal = repository.createCategory("Personal").getOrThrow()
            repository.createItem(
                work.id,
                "GitLab Production",
                listOf(ContentBlockDto(type = "secret", label = "Token", value = "glpat-xxxxx")),
            ).getOrThrow()
            repository.createItem(
                personal.id,
                "Home WiFi",
                listOf(ContentBlockDto(type = "note", value = "Router is in the office closet")),
            ).getOrThrow()

            val viewModel = HomeViewModel(repository)
            advanceUntilIdle()
            val callsAfterInitialLoad = api.listVaultItemsCallCount
            assertEquals(1, callsAfterInitialLoad)

            viewModel.onSearchQueryChange("token")
            var state = viewModel.uiState.value
            assertTrue(state.isSearching)
            assertEquals(1, state.searchResults.size)
            assertEquals("GitLab Production", state.searchResults.first().title)

            viewModel.onSearchQueryChange("closet")
            state = viewModel.uiState.value
            assertEquals(1, state.searchResults.size)
            assertEquals("Home WiFi", state.searchResults.first().title)

            viewModel.onSearchQueryChange("nothing matches this")
            assertTrue(viewModel.uiState.value.searchResults.isEmpty())

            // Searching must never re-fetch: still exactly the one call made by refresh().
            assertEquals(callsAfterInitialLoad, api.listVaultItemsCallCount)
        }

    @Test
    fun `clearing the search query restores categories and recent items view`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        repository.createItem(cat.id, "Item A", listOf(ContentBlockDto(type = "note", value = "n"))).getOrThrow()

        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        viewModel.onSearchQueryChange("item")
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.onSearchQueryChange("")
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(1, viewModel.uiState.value.recentItems.size)
    }
}
