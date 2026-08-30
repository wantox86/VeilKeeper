package id.quezacolt.veilkeeper.ui.vault

import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeVaultApi
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.ui.auth.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddItemViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeVaultApi
    private lateinit var repository: VaultRepository
    private var categoryId: Long = 0

    @Before
    fun setUp() = runTest(mainDispatcherRule.testDispatcher) {
        api = FakeVaultApi()
        repository = VaultRepository(api, ioDispatcher = mainDispatcherRule.testDispatcher, computeDispatcher = mainDispatcherRule.testDispatcher)
        AuthSessionHolder.set("token", ByteArray(32).also(SecureRandom()::nextBytes))
        categoryId = repository.createCategory("Work").getOrThrow().id
    }

    @After
    fun tearDown() {
        AuthSessionHolder.clear()
    }

    @Test
    fun `save without a title shows a validation error and does not call the API`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.addBlock("text", "Label", "value")

        viewModel.save()

        assertEquals("Title is required", viewModel.uiState.value.errorMessage)
        assertTrue(api.items.isEmpty())
    }

    @Test
    fun `save without any blocks shows a validation error`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.onTitleChange("My Item")

        viewModel.save()

        assertEquals("Add at least one piece of content", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `save with a title and blocks encrypts and uploads the item`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.onTitleChange("GitLab Production")
        viewModel.addBlock("text", "Username", "wawan")
        viewModel.addBlock("secret", "Token", "glpat-xxxxx")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(1, api.items.size)
        val stored = api.items.values.first()
        assertFalse("stored payload must not contain the plaintext title", stored.encryptedPayload.contains("GitLab Production"))
        assertFalse("stored payload must not contain the plaintext secret", stored.encryptedPayload.contains("glpat-xxxxx"))
    }

    @Test
    fun `removeBlock removes the block at the given index`() {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.addBlock("text", "A", "1")
        viewModel.addBlock("text", "B", "2")

        viewModel.removeBlock(0)

        assertEquals(1, viewModel.uiState.value.blocks.size)
        assertEquals("2", viewModel.uiState.value.blocks.first().value)
    }

    // --- Sprint 5: attachments -------------------------------------------

    @Test
    fun `save with only a pending image creates the item then uploads and links the attachment`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.onTitleChange("Screenshot")
        viewModel.addPendingImage("vpn.jpg", "image/jpeg", "fake-jpeg-bytes".toByteArray())

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(1, api.items.size)
        assertEquals(1, api.attachments.size)

        val storedItem = api.items.values.first()
        assertFalse("stored payload must not contain the plaintext filename", storedItem.encryptedPayload.contains("vpn.jpg"))

        val savedItem = repository.getItem(storedItem.id).getOrThrow()
        val imageBlock = savedItem.content.single { it.type == "image" }
        assertEquals("vpn.jpg", imageBlock.label)
        assertEquals(api.attachments.keys.first().toString(), imageBlock.value)
    }

    @Test
    fun `save with both text blocks and a pending image links both`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.onTitleChange("Mixed Item")
        viewModel.addBlock("text", "Host", "prod-1")
        viewModel.addPendingImage("diagram.png", "image/jpeg", "fake-png-bytes".toByteArray())

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saved)
        val savedItem = repository.getItem(api.items.keys.first()).getOrThrow()
        assertEquals(2, savedItem.content.size)
        assertTrue(savedItem.content.any { it.type == "text" && it.value == "prod-1" })
        assertTrue(savedItem.content.any { it.type == "image" && it.label == "diagram.png" })
    }

    @Test
    fun `removePendingImage removes the image at the given index`() {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.addPendingImage("a.jpg", "image/jpeg", "a".toByteArray())
        viewModel.addPendingImage("b.jpg", "image/jpeg", "b".toByteArray())

        viewModel.removePendingImage(0)

        assertEquals(1, viewModel.uiState.value.pendingImages.size)
        assertEquals("b.jpg", viewModel.uiState.value.pendingImages.first().filename)
    }

    @Test
    fun `save with a title and only a pending image does not require text blocks`() = runTest {
        val viewModel = AddItemViewModel(repository, categoryId)
        viewModel.onTitleChange("Only Image")
        viewModel.addPendingImage("only.jpg", "image/jpeg", "bytes".toByteArray())

        viewModel.save()

        assertNull("adding only an image must satisfy the 'at least one piece of content' rule", viewModel.uiState.value.errorMessage)
    }
}
