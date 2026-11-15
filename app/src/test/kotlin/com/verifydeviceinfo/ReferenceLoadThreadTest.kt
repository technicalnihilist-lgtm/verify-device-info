package com.verifydeviceinfo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.ReferenceRepository
import com.verifydeviceinfo.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the reference lookup of a scan runs.
 *
 * `AssetReferenceRepository` loads its data lazily and parse-once: the first `match` of a process
 * opens the bundled `device_reference.json` asset, reads 14.9 KB, parses it and builds the matching
 * index. `ScanCoordinator.start` runs the scan block on the view model's main-thread scope, and
 * `provider.collect` only hops to `Dispatchers.IO` for its own stages, so that read and parse used
 * to happen on the UI thread — a StrictMode disk read, and the first frame or two of the scanning
 * animation dropped on a cold start.
 *
 * These tests drive the real `AppViewModel.scan` with a repository that records the thread it is
 * asked on. The values are the fixture's, not a handset's: the point is the thread, and that the
 * answer still reaches the report.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReferenceLoadThreadTest {

    /** Records the thread of every `match` call and answers with a fixed match. */
    private class RecordingReferenceRepository(
        private val answer: ReferenceMatch? = null
    ) : ReferenceRepository {

        val threads: MutableList<Thread> = CopyOnWriteArrayList()
        val arguments: MutableList<Triple<String, String, String>> = CopyOnWriteArrayList()

        override fun match(model: String, brand: String, device: String): ReferenceMatch? {
            threads += Thread.currentThread()
            arguments += Triple(model, brand, device)
            return answer
        }

        override fun all(): List<ReferenceDevice> = answer?.let { listOf(it.device) }.orEmpty()

        override fun sourceSummary(): String = "test reference set"
    }

    private fun referenceDevice() = ReferenceDevice(
        modelPatterns = listOf("Pixel 8"),
        brand = "Google",
        marketingName = "Pixel 8",
        soc = "Tensor G3",
        socManufacturer = "Google",
        releaseDate = "2023-10-12",
        ramOptionsGb = listOf(8),
        storageOptionsGb = listOf(128),
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        densityDpi = 428,
        refreshRateHz = 120f,
        rearCameraCount = 2,
        frontCameraCount = 1,
        mainCameraMegapixels = 50.0,
        batteryMah = 4575,
        expectedSensors = listOf("accelerometer"),
        sourceUrl = "https://example.invalid/pixel-8",
        sourceDate = "2024-01-01",
        sourceName = "Test fixture"
    )

    private fun viewModel(repository: ReferenceRepository): AppViewModel {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        return AppViewModel(application, AppContainer(application, referenceRepositoryOverride = repository))
    }

    /** Runs the debug-only mock scan and returns the report it published. */
    private fun scannedReport(vm: AppViewModel): DeviceReport {
        vm.scan(useMockData = true)
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            main.idleFor(Duration.ofMillis(250))
            vm.state.value.report?.let { return it }
            if (vm.state.value.error != null) fail("mock scan failed: ${vm.state.value.error}")
            Thread.sleep(5)
        }
        fail("the mock scan produced no report")
        error("unreachable")
    }

    @Test
    fun theReferenceLookupDoesNotRunOnTheMainThread() {
        val repository = RecordingReferenceRepository()
        val report = scannedReport(viewModel(repository))

        assertNotNull("the scan must still produce a report", report)
        assertEquals("the lookup must run exactly once per scan", 1, repository.threads.size)
        val mainThread = Looper.getMainLooper().thread
        repository.threads.forEach { thread ->
            assertTrue(
                "the reference lookup ran on the main thread ($mainThread); it reads the " +
                    "bundled asset on the first scan of a process and must not do that on the UI " +
                    "thread",
                thread !== mainThread
            )
        }
    }

    @Test
    fun theLookupStillSeesTheSnapshotIdentity() {
        val repository = RecordingReferenceRepository()
        val report = scannedReport(viewModel(repository))

        assertEquals(1, repository.arguments.size)
        val (model, brand, device) = repository.arguments.single()
        assertEquals(report.snapshot.identity.model, model)
        assertEquals(report.snapshot.identity.brand, brand)
        assertEquals(report.snapshot.identity.device, device)
    }

    @Test
    fun theMatchedReferenceStillReachesTheReport() {
        val match = ReferenceMatch(referenceDevice(), "Pixel 8")
        val repository = RecordingReferenceRepository(match)
        val vm = viewModel(repository)

        val report = scannedReport(vm)

        // The reference is published with the report, so a lookup that answered off the main
        // thread still lands in the state the screens read.
        assertSame(match, vm.state.value.reference)
        assertNotNull(report)
    }

    @Test
    fun anUnmatchedScanStillProducesAReport() {
        val repository = RecordingReferenceRepository(answer = null)
        val vm = viewModel(repository)

        val report = scannedReport(vm)

        assertNull(vm.state.value.reference)
        assertNotNull("no reference data is not a failed scan", report)
    }
}
