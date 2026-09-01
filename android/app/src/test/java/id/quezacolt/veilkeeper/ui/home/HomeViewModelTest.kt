package id.quezacolt.veilkeeper.ui.home

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeVaultApi
import id.quezacolt.veilkeeper.data.VaultLockState
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

    // Post-launch fix: Home auto-refresh on return-to-screen (see HomeScreen's
    // ON_RESUME LifecycleEventObserver calling refreshSilently()).

    @Test
    fun `refreshSilently picks up items added after the initial load, without touching isLoading`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cat = repository.createCategory("Work").getOrThrow()
            val viewModel = HomeViewModel(repository)
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.recentItems.size)

            // Simulates a new item being added from the Add Item screen while
            // Home was on the back stack, then the user navigating back.
            repository.createItem(cat.id, "New Item", listOf(ContentBlockDto(type = "note", value = "n"))).getOrThrow()
            viewModel.refreshSilently()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, state.recentItems.size)
            assertEquals("New Item", state.recentItems.first().title)
            // Silent refresh must never flip on the full-screen loading state --
            // that would flash the whole screen away on every back-navigation.
            assertFalse(state.isLoading)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `refreshSilently is a no-op while a refresh is already in flight`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = HomeViewModel(repository)
        // Called synchronously right after construction, before advanceUntilIdle
        // lets init's own refresh() coroutine complete -- mirrors HomeScreen's
        // ON_RESUME firing around the same time as the ViewModel's init block.
        val callsBeforeGuardedCall = api.listVaultItemsCallCount
        viewModel.refreshSilently()
        assertEquals(callsBeforeGuardedCall, api.listVaultItemsCallCount)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // Post-launch fix: pull-to-refresh gesture (HomeScreen's PullToRefreshBox).

    @Test
    fun `onPullToRefresh sets and clears isRefreshing without ever setting isLoading`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = HomeViewModel(repository)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isRefreshing)

            viewModel.onPullToRefresh()
            // Immediately after the call (before the coroutine completes),
            // isRefreshing must already be true so the pull indicator shows up
            // right away -- and isLoading must stay false so PullToRefreshBox's
            // content isn't swapped out for the full-screen loader mid-gesture.
            assertTrue(viewModel.uiState.value.isRefreshing)
            assertFalse(viewModel.uiState.value.isLoading)

            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `onPullToRefresh picks up newly added items`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.recentItems.size)

        repository.createItem(cat.id, "Pulled Item", listOf(ContentBlockDto(type = "note", value = "n"))).getOrThrow()
        viewModel.onPullToRefresh()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.recentItems.size)
        assertEquals("Pulled Item", viewModel.uiState.value.recentItems.first().title)
    }

    // Post-launch fixes batch 3: the "vault is locked / Retry -> infinite
    // loop" bug. Root cause: auto-lock (AutoLockManager, see CLAUDE.md's
    // Sprint 3 entry) can clear the in-memory VDK while Home is still the
    // visible screen (e.g. mid pull-to-refresh, or the ON_RESUME-triggered
    // refreshSilently() racing AutoLockManager's own lifecycle callback) --
    // every repository read then failed with VaultError.NotUnlocked, which
    // pre-fix was rendered as a generic VeilKeeperErrorState+Retry, and
    // Retry just re-ran the same doomed call forever, because the vault
    // really was locked and nothing ever told AuthSessionHolder so.

    @Test
    fun `refreshSilently does not surface a retryable error when the vault gets locked mid-session, and drives the global lock state instead`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cat = repository.createCategory("Work").getOrThrow()
            repository.createItem(cat.id, "Item A", listOf(ContentBlockDto(type = "note", value = "n"))).getOrThrow()

            val viewModel = HomeViewModel(repository)
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.recentItems.size)
            assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)

            // Simulates auto-lock firing (VDK cleared from memory) while
            // Home is still the screen the user is looking at.
            AuthSessionHolder.lock()

            viewModel.refreshSilently()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // The bug: this used to be non-null ("vault is locked"),
            // rendered via VeilKeeperErrorState with a Retry button that
            // could never succeed.
            assertEquals(null, state.errorMessage)
            assertFalse(state.isLoading)
            // This is what actually redirects the user to the Unlock
            // screen -- MainActivity's global LaunchedEffect(lockState).
            assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
        }

    @Test
    fun `refresh does not surface a retryable error when the vault gets locked mid-session`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = HomeViewModel(repository)
            advanceUntilIdle()

            AuthSessionHolder.lock()
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(null, state.errorMessage)
            assertFalse(state.isLoading)
            assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
        }
}
