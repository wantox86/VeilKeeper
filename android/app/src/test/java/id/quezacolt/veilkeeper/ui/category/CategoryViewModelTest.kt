package id.quezacolt.veilkeeper.ui.category

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeVaultApi
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.ui.auth.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

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
    fun `loads only items belonging to this category`() = runTest(mainDispatcherRule.testDispatcher) {
        val catA = repository.createCategory("A").getOrThrow()
        val catB = repository.createCategory("B").getOrThrow()
        repository.createItem(catA.id, "In A", listOf(ContentBlockDto(type = "note", value = "x"))).getOrThrow()
        repository.createItem(catB.id, "In B", listOf(ContentBlockDto(type = "note", value = "y"))).getOrThrow()

        val viewModel = CategoryViewModel(repository, catA.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("A", state.category?.name)
        assertEquals(1, state.allItems.size)
        assertEquals("In A", state.allItems.first().title)
    }

    @Test
    fun `local search filters by title without any extra network call`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("A").getOrThrow()
        repository.createItem(cat.id, "GitLab Production", listOf(ContentBlockDto(type = "note", value = "x"))).getOrThrow()
        repository.createItem(cat.id, "Home WiFi", listOf(ContentBlockDto(type = "note", value = "y"))).getOrThrow()

        val viewModel = CategoryViewModel(repository, cat.id)
        advanceUntilIdle()

        viewModel.onQueryChange("gitlab")

        val visible = viewModel.uiState.value.visibleItems
        assertEquals(1, visible.size)
        assertEquals("GitLab Production", visible.first().title)
    }
}
