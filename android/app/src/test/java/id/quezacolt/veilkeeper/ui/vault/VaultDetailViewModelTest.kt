package id.quezacolt.veilkeeper.ui.vault

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeVaultApi
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.ui.auth.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VaultDetailViewModelTest {

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
    fun `loads and decrypts the item for display`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val item = repository.createItem(
            cat.id,
            "GitLab Production",
            listOf(ContentBlockDto(type = "secret", label = "Token", value = "glpat-xxxxx")),
        ).getOrThrow()

        val viewModel = VaultDetailViewModel(repository, item.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("GitLab Production", state.item?.title)
        assertEquals("glpat-xxxxx", state.item?.content?.first()?.value)
    }

    @Test
    fun `delete marks the item deleted and removes it from the backing store`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val item = repository.createItem(cat.id, "Temp", listOf(ContentBlockDto(type = "note", value = "x"))).getOrThrow()

        val viewModel = VaultDetailViewModel(repository, item.id)
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.deleted)
        assertTrue(repository.getItem(item.id).isFailure)
    }

    @Test
    fun `nonexistent item surfaces an error instead of crashing`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = VaultDetailViewModel(repository, itemId = 9999L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage != null)
    }

    // --- Sprint 5: attachments -------------------------------------------

    @Test
    fun `loadAttachmentImage downloads and decrypts, transitioning Loading to Loaded`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val item = repository.createItem(cat.id, "With Image", emptyList()).getOrThrow()
        val ref = repository.uploadAttachment(item.id, "shot.jpg", "image/jpeg", "fake-bytes".toByteArray()).getOrThrow()

        val viewModel = VaultDetailViewModel(repository, item.id)
        advanceUntilIdle()

        viewModel.loadAttachmentImage(ref.id)
        assertTrue(
            "expected Loading state immediately after the call, before the coroutine completes",
            viewModel.uiState.value.attachmentImages[ref.id] is AttachmentImageState.Loading,
        )
        advanceUntilIdle()

        val loaded = viewModel.uiState.value.attachmentImages[ref.id]
        assertTrue(loaded is AttachmentImageState.Loaded)
        assertArrayEquals("fake-bytes".toByteArray(), (loaded as AttachmentImageState.Loaded).bytes)
        assertEquals("image/jpeg", loaded.mimeType)
    }

    @Test
    fun `loadAttachmentImage does not re-fetch an already-loaded attachment`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val item = repository.createItem(cat.id, "With Image", emptyList()).getOrThrow()
        val ref = repository.uploadAttachment(item.id, "shot.jpg", "image/jpeg", "fake-bytes".toByteArray()).getOrThrow()

        val viewModel = VaultDetailViewModel(repository, item.id)
        advanceUntilIdle()
        viewModel.loadAttachmentImage(ref.id)
        advanceUntilIdle()

        // Deleting the attachment out from under the ViewModel: if
        // loadAttachmentImage re-fetched, the state would flip to Error.
        repository.deleteAttachment(item.id, ref.id).getOrThrow()
        viewModel.loadAttachmentImage(ref.id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.attachmentImages[ref.id] is AttachmentImageState.Loaded)
    }

    @Test
    fun `loadAttachmentImage surfaces a failure as an Error state instead of crashing`() = runTest(mainDispatcherRule.testDispatcher) {
        val cat = repository.createCategory("Work").getOrThrow()
        val item = repository.createItem(cat.id, "No Image", emptyList()).getOrThrow()

        val viewModel = VaultDetailViewModel(repository, item.id)
        advanceUntilIdle()

        viewModel.loadAttachmentImage(999999L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.attachmentImages[999999L] is AttachmentImageState.Error)
    }
}
