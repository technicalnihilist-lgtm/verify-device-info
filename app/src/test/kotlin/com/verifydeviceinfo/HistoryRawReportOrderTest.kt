package com.verifydeviceinfo

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import com.verifydeviceinfo.ui.AppViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Which stored report a history read is allowed to publish.
 *
 * Expanding a card reads that entry's stored JSON off disk and puts it in the expanded card. Two
 * taps in a row — expand one entry, then another — leave two reads in flight, and nothing used to
 * order their completions: the slower read for the entry the user had moved away from could land
 * last, and the card now open rendered a different entry's report.
 *
 * These tests drive the real view model against the real file-backed history, with two reports
 * actually stored, and assert that only the newest request may publish. The first read is started
 * and superseded before either read can answer, because the ticket is taken synchronously by the
 * caller while the reads themselves are dispatched — so the ordering under test is the order the
 * taps happened in, not a race the test is hoping to reproduce.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryRawReportOrderTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun report(model: String): DeviceReport {
        val snapshot = TestFixtures.snapshot(identity = TestFixtures.identity(model = model))
        val session = DiagnosticSession()
        val reference = TestFixtures.referenceMatch()
        return DeviceReport(
            snapshot = snapshot,
            authenticity = DefaultAuthenticityEngine().analyze(snapshot, reference),
            parts = DefaultPartsProvider().assess(snapshot, reference, session),
            age = DefaultAgeEstimator().estimate(snapshot, reference, null),
            integrity = DefaultIntegrityAnalyzer().analyze(snapshot),
            scores = DefaultScoreEngine().score(
                snapshot,
                DefaultPartsProvider().assess(snapshot, reference, session),
                DefaultIntegrityAnalyzer().analyze(snapshot),
                session
            ),
            session = session,
            generatedAtMillis = TestFixtures.NOW,
            appVersion = "test",
            redacted = true
        )
    }

    /** Stores two reports and returns the ids of the older and the newer one, in that order. */
    private fun storeTwoReports(viewModel: AppViewModel): Pair<String, String> = runBlocking {
        val first = viewModel.container.history.save(report("SM-FIRST"), null)
        val second = viewModel.container.history.save(report("SM-SECOND"), null)
        first.id to second.id
    }

    /**
     * Pumps the main looper until [delivered] reports [expected] deliveries, or the deadline passes.
     *
     * The read itself runs on `Dispatchers.IO` and posts its continuation back to the main looper,
     * so the test has to let real time pass *and* drain the looper: idling alone would return
     * before the IO thread had answered.
     */
    private fun awaitDeliveries(expected: Int, delivered: () -> Int, graceMillis: Long = 20_000L) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < deadline) {
            main.idleFor(Duration.ofMillis(100))
            if (delivered() >= expected) return
            Thread.sleep(5)
        }
    }

    @Test
    fun aReadThatWasSupersededNeverPublishes() {
        val viewModel = AppViewModel(application)
        val (firstId, secondId) = storeTwoReports(viewModel)

        var firstDelivered: String? = null
        var secondDelivered: String? = null
        var deliveries = 0
        viewModel.loadRawReport(firstId) { firstDelivered = it; deliveries++ }
        viewModel.loadRawReport(secondId) { secondDelivered = it; deliveries++ }
        awaitDeliveries(expected = 1, delivered = { deliveries })
        // Give the superseded read the same chance to publish late.
        awaitDeliveries(expected = 2, delivered = { deliveries }, graceMillis = 3_000L)

        // The first entry's report must not be shown under the second entry's card, which is what
        // the unguarded callback did whenever it answered last.
        assertNull(firstDelivered)
        assertTrue(
            "the newest request must still publish, got: ${secondDelivered?.take(120)}",
            secondDelivered?.contains("SM-SECOND") == true
        )
    }

    @Test
    fun aSingleReadStillPublishesItsOwnReport() {
        val viewModel = AppViewModel(application)
        val (firstId, _) = storeTwoReports(viewModel)

        var delivered: String? = null
        var deliveries = 0
        viewModel.loadRawReport(firstId) { delivered = it; deliveries++ }
        awaitDeliveries(expected = 1, delivered = { deliveries })

        assertTrue(
            "an uncontested read must publish its report, got: ${delivered?.take(120)}",
            delivered?.contains("SM-FIRST") == true
        )
    }

    @Test
    fun theNewestRequestWinsWhateverOrderTheReadsAnswerIn() {
        val viewModel = AppViewModel(application)
        val (firstId, secondId) = storeTwoReports(viewModel)

        var delivered: String? = null
        // Two requests, then a third for the older entry: the last tap is the one that counts, so
        // the older entry's report is the one that must appear — and it must appear exactly once.
        var deliveries = 0
        viewModel.loadRawReport(secondId) { delivered = it; deliveries++ }
        viewModel.loadRawReport(firstId) { delivered = it; deliveries++ }
        awaitDeliveries(expected = 1, delivered = { deliveries })
        // A second, superseded delivery must never arrive: pump again and let it try.
        awaitDeliveries(expected = 2, delivered = { deliveries }, graceMillis = 3_000L)

        assertEquals(1, deliveries)
        assertTrue(
            "the last tap must decide what is shown, got: ${delivered?.take(120)}",
            delivered?.contains("SM-FIRST") == true
        )
    }
}
