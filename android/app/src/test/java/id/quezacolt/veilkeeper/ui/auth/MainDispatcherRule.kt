package id.quezacolt.veilkeeper.ui.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps Dispatchers.Main for a test dispatcher, needed for ViewModel/
 * viewModelScope tests. [testDispatcher] is exposed so a repository under
 * test can be constructed with the *same* dispatcher for its IO/compute
 * work -- using a second, independent TestDispatcher there would trip
 * kotlinx-coroutines-test's "different schedulers" guard when a ViewModel
 * test calls advanceUntilIdle() (see Sprint 2 VaultRepository-backed
 * ViewModel tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
