package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Sprint 4 global search matcher (SPEC-BASE.md Section
 * 16 / Phase 4). Pure logic, no network/repository involved -- this is
 * exactly what makes it possible to guarantee no plaintext query ever
 * reaches the backend: matching happens entirely against in-memory
 * [DecryptedVaultItem]s already decrypted by the caller.
 */
class VaultSearchTest {

    private fun item(
        title: String,
        content: List<ContentBlockDto> = emptyList(),
    ) = DecryptedVaultItem(id = 1, categoryId = 1, title = title, content = content, updatedAt = "2026-01-01T00:00:00Z")

    @Test
    fun `blank query matches everything`() {
        val i = item("GitLab Production")
        assertTrue(VaultSearch.matches(i, ""))
        assertTrue(VaultSearch.matches(i, "   "))
    }

    @Test
    fun `matches title case-insensitively`() {
        val i = item("GitLab Production")
        assertTrue(VaultSearch.matches(i, "gitlab"))
        assertTrue(VaultSearch.matches(i, "PRODUCTION"))
        assertFalse(VaultSearch.matches(i, "bitbucket"))
    }

    @Test
    fun `matches a content block label`() {
        val i = item("Server creds", listOf(ContentBlockDto(type = "text", label = "Username", value = "wawan")))
        assertTrue(VaultSearch.matches(i, "username"))
    }

    @Test
    fun `matches a text block value`() {
        val i = item("Server creds", listOf(ContentBlockDto(type = "text", label = "Server", value = "gitlab.company.local")))
        assertTrue(VaultSearch.matches(i, "company.local"))
    }

    @Test
    fun `matches note content`() {
        val i = item("GitLab Production", listOf(ContentBlockDto(type = "note", value = "Expire: December 2026.")))
        assertTrue(VaultSearch.matches(i, "expire"))
    }

    @Test
    fun `matches a secret block label and value (matching only, never displayed)`() {
        val i = item("GitLab Production", listOf(ContentBlockDto(type = "secret", label = "Token", value = "glpat-xxxxx")))
        assertTrue(VaultSearch.matches(i, "token"))
        assertTrue(VaultSearch.matches(i, "glpat"))
    }

    @Test
    fun `no match returns false`() {
        val i = item("VPN", listOf(ContentBlockDto(type = "note", value = "internal use only")))
        assertFalse(VaultSearch.matches(i, "openai"))
    }

    @Test
    fun `filter keeps only matching items and preserves order`() {
        val items = listOf(
            item("GitLab Production"),
            item("OpenAI API"),
            item("Home WiFi", listOf(ContentBlockDto(type = "text", label = "SSID", value = "MyGitLabNet"))),
        )

        val results = VaultSearch.filter(items, "gitlab")

        assertEquals(2, results.size)
        assertEquals("GitLab Production", results[0].title)
        assertEquals("Home WiFi", results[1].title)
    }

    @Test
    fun `filter with blank query returns the original list untouched`() {
        val items = listOf(item("A"), item("B"))
        assertEquals(items, VaultSearch.filter(items, ""))
    }
}
