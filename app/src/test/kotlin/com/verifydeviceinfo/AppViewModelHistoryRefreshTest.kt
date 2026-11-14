package com.verifydeviceinfo

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.domain.DeviceInformationProvider
import com.verifydeviceinfo.domain.LocalDiagnosticHistory
import com.verifydeviceinfo.ui.AppViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What a history refresh is allowed to overwrite.
 *
 * `refreshHistory` used to publish `_state.value.copy(history = container.history.list())`. The
 * receiver of `copy` is evaluated before its argument, and `list()` is a suspend call that reads
 * the disk, so the copy was taken *before* the read started and written back after it finished —
 * over anything published in between. The constructor queues one refresh, so a scan that failed
 * fast, or any action the user took before the first read landed, had its message silently
 * discarded. The fix reads the list into a local and merges it with `update`, whose CAS loop
 * re-reads the current state, so only the `history` field moves.
 *
 * The fixture is a history whose `list()` parks on a gate, which is what makes the window
 * deterministic: the refresh is provably mid-read when the scan publishes its failure. Nothing
 * rendered changes — the same entries are listed either way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppViewModelHistoryRefreshTest {

    private val storedEntries = listOf(
        HistoryEntry(
            id = "entry-1",
            timestampMillis = 1_700_000_000_000L,
            modelLabel = "Fixture device",
            riskLevel = RiskLevel.LOW,
            hardwareScore = 90,
            authenticityScore = 80,
            issueCount = 0,
            reportPath = null
        )
    )

    /** A stored history that can be made to answer slowly, or immediately. */
    private class GatedHistory(
        private val entries: List<HistoryEntry>,
        private val gate: CompletableDeferred<Unit>?
    ) : LocalDiagnosticHistory {
        var listCalls = 0
            private set

        override suspend fun save(report: DeviceReport, reportPath: String?): HistoryEntry =
            throw UnsupportedOperationException("this fixture never stores a report")

        override suspend fun list(): List<HistoryEntry> {
            listCalls++
            gate?.await()
            return entries
        }

        override suspend fun loadRaw(reportId: String): String? = null

        override suspend fun clear() = Unit
    }

    /** A scan that fails at once, so its failure is published inside a known window. */
    private class FailingProvider : DeviceInformationProvider {
        override val platform: Platform = Platform.ANDROID
        override suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot =
            throw IllegalStateException("boom")
    }

    private fun viewModel(history: LocalDiagnosticHistory): AppViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return AppViewModel(
            application,
            AppContainer(application, deviceInformationProviderOverride = FailingProvider(), historyOverride = history)
        )
    }

    /** Idles the main looper until [condition] holds, so a resumed coroutine is not read too early. */
    private fun await(vm: AppViewModel, condition: (AppViewModel) -> Boolean) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            main.idleFor(Duration.ofMillis(250))
            if (condition(vm)) return
            Thread.sleep(5)
        }
        throw AssertionError("the view model never reached the expected state: ${vm.state.value}")
    }

    @Test
    fun aRefreshThatWasReadingTheDiskDoesNotEraseAnErrorPublishedMeanwhile() {
        val gate = CompletableDeferred<Unit>()
        val history = GatedHistory(storedEntries, gate)
        val vm = viewModel(history)

        // The constructor's refresh is now parked inside list(), holding a copy of the state taken
        // before it suspended. Nothing has answered it yet.
        await(vm) { history.listCalls == 1 }
        assertEquals("the constructor must have queued exactly one refresh", 1, history.listCalls)

        vm.scan()
        await(vm) { it.state.value.error != null }
        assertEquals("Scan failed: boom", vm.state.value.error)

        // The read now answers, and the refresh writes its result back.
        gate.complete(Unit)
        await(vm) { it.state.value.history.isNotEmpty() }

        assertEquals(
            "a message published while the refresh was reading must survive it",
            "Scan failed: boom",
            vm.state.value.error
        )
        assertEquals(storedEntries, vm.state.value.history)
    }

    @Test
    fun theRefreshStillPublishesTheStoredHistory() {
        val history = GatedHistory(storedEntries, gate = null)
        val vm = viewModel(history)

        await(vm) { it.state.value.history.isNotEmpty() }

        assertEquals(storedEntries, vm.state.value.history)
        assertEquals("a plain refresh publishes nothing else", 1, history.listCalls)
    }
}
