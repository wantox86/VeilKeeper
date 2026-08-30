package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * Exercises the Sprint 2 end-to-end acceptance flow from CLAUDE.md at the
 * repository layer: create category -> create vault item (encrypt
 * client-side) -> upload (fake API) -> retrieve -> decrypt -> matches the
 * original content exactly. Also verifies the fake API "server" only ever
 * sees ciphertext, never the plaintext title/content.
 */
class VaultRepositoryTest {

    private lateinit var api: FakeVaultApi
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        api = FakeVaultApi()
        repository = VaultRepository(api)

        val vdk = ByteArray(32).also(SecureRandom()::nextBytes)
        AuthSessionHolder.set(sessionToken = "test-session-token", vaultDataKey = vdk)
    }

    @After
    fun tearDown() {
        AuthSessionHolder.clear()
    }

    @Test
    fun `create category then create item, retrieve and decrypt matches original`() = runTest {
        val category = repository.createCategory("Servers").getOrThrow()

        val content = listOf(
            ContentBlockDto(type = "text", label = "Host", value = "prod-1.internal"),
            ContentBlockDto(type = "secret", label = "Password", value = "hunter2"),
        )
        val created = repository.createItem(category.id, "Prod Box", content).getOrThrow()

        assertEquals("Prod Box", created.title)
        assertEquals(content, created.content)

        // The fake API stands in for the server: verify it only holds
        // opaque base64 ciphertext, not the plaintext title/values.
        val storedDto = api.items.getValue(created.id)
        assertTrue("stored payload must not contain the plaintext title", !storedDto.encryptedPayload.contains("Prod Box"))
        assertTrue("stored payload must not contain the plaintext secret", !storedDto.encryptedPayload.contains("hunter2"))

        val fetched = repository.getItem(created.id).getOrThrow()
        assertEquals(created.title, fetched.title)
        assertEquals(created.content, fetched.content)
    }

    @Test
    fun `listItems decrypts every item and returns matching domain models`() = runTest {
        val category = repository.createCategory("Notes").getOrThrow()
        repository.createItem(category.id, "First", listOf(ContentBlockDto(type = "note", value = "one"))).getOrThrow()
        repository.createItem(category.id, "Second", listOf(ContentBlockDto(type = "note", value = "two"))).getOrThrow()

        val items = repository.listItems(category.id).getOrThrow()

        assertEquals(2, items.size)
        assertEquals(setOf("First", "Second"), items.map { it.title }.toSet())
    }

    @Test
    fun `updateItem re-encrypts with a fresh ciphertext`() = runTest {
        val category = repository.createCategory("Misc").getOrThrow()
        val created = repository.createItem(category.id, "Title", listOf(ContentBlockDto(type = "text", value = "v1"))).getOrThrow()
        val originalCiphertext = api.items.getValue(created.id).encryptedPayload

        val updated = repository.updateItem(created.id, null, "Title", listOf(ContentBlockDto(type = "text", value = "v2"))).getOrThrow()

        assertEquals("v2", updated.content.first().value)
        assertNotEquals("ciphertext must change after an update", originalCiphertext, api.items.getValue(created.id).encryptedPayload)
    }

    @Test
    fun `deleteCategory without reassignTo moves items to Uncategorized`() = runTest {
        val category = repository.createCategory("Temp").getOrThrow()
        val item = repository.createItem(category.id, "Orphan", listOf(ContentBlockDto(type = "note", value = "x"))).getOrThrow()

        repository.deleteCategory(category.id).getOrThrow()

        val categories = repository.listCategories().getOrThrow()
        val uncategorized = categories.first { it.isUncategorized }
        val survivedItem = repository.getItem(item.id).getOrThrow()
        assertEquals(uncategorized.id, survivedItem.categoryId)
    }

    @Test
    fun `operations fail with NotUnlocked when there is no VDK`() = runTest {
        AuthSessionHolder.clear()
        // Session token still needed for the (never-reached) API call, but
        // the missing VDK must short-circuit before any network call.
        val result = repository.listItems()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VaultRepository.VaultError.NotUnlocked)
    }

    @Test
    fun `server error maps to a VaultError`() = runTest {
        api.forcedErrorCode = 500
        val result = repository.listCategories()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VaultRepository.VaultError.ServerError)
    }
}
