package id.quezacolt.veilkeeper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `defaults match the spec's suggested defaults when nothing is persisted yet`() {
        val repo = SettingsRepository(InMemorySettingsStorage())
        assertEquals(AutoLockTimeout.DEFAULT, repo.autoLockTimeout.value)
        assertEquals(ClipboardClearDelay.DEFAULT, repo.clipboardClearDelay.value)
    }

    @Test
    fun `setAutoLockTimeout persists and updates the state flow`() {
        val storage = InMemorySettingsStorage()
        val repo = SettingsRepository(storage)

        repo.setAutoLockTimeout(AutoLockTimeout.IMMEDIATE)

        assertEquals(AutoLockTimeout.IMMEDIATE, repo.autoLockTimeout.value)
        // A fresh repository instance over the same storage must see the persisted value.
        assertEquals(AutoLockTimeout.IMMEDIATE, SettingsRepository(storage).autoLockTimeout.value)
    }

    @Test
    fun `setClipboardClearDelay persists and updates the state flow`() {
        val storage = InMemorySettingsStorage()
        val repo = SettingsRepository(storage)

        repo.setClipboardClearDelay(ClipboardClearDelay.SIXTY_SECONDS)

        assertEquals(ClipboardClearDelay.SIXTY_SECONDS, repo.clipboardClearDelay.value)
        assertEquals(ClipboardClearDelay.SIXTY_SECONDS, SettingsRepository(storage).clipboardClearDelay.value)
    }

    @Test
    fun `unknown persisted value falls back to default instead of crashing`() {
        val storage = InMemorySettingsStorage().apply { putString("auto_lock_timeout_unrelated_key", "garbage") }
        val repo = SettingsRepository(storage)
        assertEquals(AutoLockTimeout.DEFAULT, repo.autoLockTimeout.value)
    }
}
