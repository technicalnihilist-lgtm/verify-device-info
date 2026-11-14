package com.verifydeviceinfo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verifydeviceinfo.AppContainer
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.core.util.LatestRequest
import com.verifydeviceinfo.core.util.ScanCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val snapshot: DeviceSnapshot? = null,
    val reference: ReferenceMatch? = null,
    val report: DeviceReport? = null,
    val session: DiagnosticSession = DiagnosticSession(),
    val history: List<HistoryEntry> = emptyList(),
    val purchaseDateMillis: Long? = null,
    val error: String? = null,
    val message: String? = null,
    val exportFile: File? = null,
    val redacted: Boolean = true,
    val usedMockData: Boolean = false
)

/**
 * [container] is a constructor parameter rather than a field built in the body so a test can supply
 * a graph whose provider or diagnostics engine fails on purpose; `@JvmOverloads` keeps the
 * `(Application)` constructor the framework's default `AndroidViewModelFactory` reflects on.
 */
class AppViewModel @JvmOverloads constructor(
    application: Application,
    val container: AppContainer = AppContainer(application)
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        UiState(purchaseDateMillis = container.purchaseDateMillis, redacted = container.redactByDefault)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshHistory()
    }

    /**
     * Keeps the scan single-flight and makes the newest request win: a scan that is already running
     * is cancelled rather than making the new request a no-op, so a scan wedged in a blocking
     * collector cannot lock the app out of rescanning. See [ScanCoordinator].
     */
    private val scans = ScanCoordinator(viewModelScope)

    /** Orders the history screen's stored-report reads; see [loadRawReport]. */
    private val rawReportRequests = LatestRequest()

    fun scan(useMockData: Boolean = false) {
        scans.start { generation ->
            publish(generation) {
                it.copy(
                    scanning = true, error = null, message = null, exportFile = null,
                    progress = ScanProgress(0, 8, "Preparing"), usedMockData = useMockData
                )
            }
            try {
                val provider = if (useMockData) container.mockProvider else container.deviceInformationProvider
                if (provider == null) {
                    publish(generation) {
                        it.copy(
                            scanning = false, error = "Mock data is only available in debug builds."
                        )
                    }
                    return@start
                }
                val snapshot = provider.collect { progress ->
                    publish(generation) { it.copy(progress = progress) }
                }
                val reference = container.referenceRepository.match(
                    snapshot.identity.model, snapshot.identity.brand, snapshot.identity.device
                )
                val authenticity = container.authenticityEngine.analyze(snapshot, reference)
                val session = _state.value.session
                val parts = container.partsProvider.assess(snapshot, reference, session)
                val age = container.ageEstimator.estimate(snapshot, reference, container.purchaseDateMillis)
                val integrity = container.integrityAnalyzer.analyze(snapshot)
                val scores = container.scoreEngine.score(snapshot, parts, integrity, session)
                val report = DeviceReport(
                    snapshot = snapshot,
                    authenticity = authenticity,
                    parts = parts,
                    age = age,
                    integrity = integrity,
                    scores = scores,
                    session = session,
                    generatedAtMillis = System.currentTimeMillis(),
                    appVersion = snapshot.appVersion,
                    redacted = _state.value.redacted
                )
                // A scan superseded while it was finishing must not write history or state.
                if (!scans.isCurrent(generation)) return@start
                container.history.save(report, null)
                publish(generation) {
                    it.copy(
                        scanning = false,
                        progress = ScanProgress(8, 8, "Complete", finished = true),
                        snapshot = snapshot,
                        reference = reference,
                        report = report,
                        message = if (snapshot.isMock) "Mock scan complete (development fixture)." else null
                    )
                }
                if (scans.isCurrent(generation)) refreshHistory()
            } catch (e: CancellationException) {
                // Superseded by a newer scan: that is not a failure and must not be reported as one.
                throw e
            } catch (e: LinkageError) {
                // An unresolved class or method is not an Exception, so without this branch it
                // left the coroutine and took the process with it instead of reporting a failed
                // scan. Same message, same state field as the branch below.
                publish(generation) {
                    it.copy(
                        scanning = false,
                        error = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } catch (e: Exception) {
                publish(generation) {
                    it.copy(
                        scanning = false,
                        error = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /** Applies a scan's state change only while that scan is still the newest one. */
    private inline fun publish(generation: Long, transform: (UiState) -> UiState) {
        if (!scans.isCurrent(generation)) return
        _state.update(transform)
    }

    fun runAutomatedDiagnostics() {
        viewModelScope.launch {
            try {
                val results = container.diagnosticsEngine.runAutomatedChecks()
                var session = _state.value.session
                results.forEach { session = session.with(it) }
                _state.value = _state.value.copy(session = session)
                rebuildReport()
            } catch (e: LinkageError) {
                // Same reasoning as the scan: a linkage failure must read as a failed diagnostics
                // run, not as a crash.
                _state.value = _state.value.copy(error = "Diagnostics failed: ${e.message}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Diagnostics failed: ${e.message}")
            }
        }
    }

    fun recordResult(result: DiagnosticResult) {
        val session = _state.value.session.with(result)
        _state.value = _state.value.copy(session = session)
        rebuildReport()
    }

    fun setPurchaseDate(millis: Long?) {
        container.purchaseDateMillis = millis
        _state.value = _state.value.copy(purchaseDateMillis = millis)
        // The one caller that changes an input the age estimate reads, so it re-derives it.
        rebuildReport(recomputeAge = true)
    }

    /**
     * Stores the redaction preference and re-stamps the report. No analyzer is re-run.
     *
     * Nothing the analysis produces reads the flag: `parts`, `age`, `integrity`, `scores` and
     * `authenticity` are computed from the snapshot, the reference and the diagnostic session, and
     * both export paths apply the flag themselves by copying `state.redacted` onto the report. A
     * full rebuild here therefore re-derived five identical objects on the main thread every time
     * the switch was flipped — measured at ~0.5 ms of analysis per flip on the JVM fixture — to
     * change a boolean. The flag and the timestamp the Report screen renders are the only things
     * that ever differed, so they are the only things written.
     */
    fun setRedacted(redacted: Boolean) {
        container.redactByDefault = redacted
        val current = _state.value
        _state.value = current.copy(
            redacted = redacted,
            report = current.report?.copy(
                redacted = redacted,
                generatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    fun exportPdf() {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            try {
                val file = container.reportGenerator.toPdf(report.copy(redacted = _state.value.redacted))
                _state.value = _state.value.copy(exportFile = file, message = "PDF ready to share.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "PDF export failed: ${e.message}")
            }
        }
    }

    fun exportJson() {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            try {
                val file = container.reportGenerator.toJsonFile(
                    report.copy(redacted = _state.value.redacted),
                    File(getApplication<Application>().cacheDir, "verify-device-info-report.json")
                )
                _state.value = _state.value.copy(exportFile = file, message = "JSON ready to share.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "JSON export failed: ${e.message}")
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(history = container.history.list())
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            container.history.clear()
            refreshHistory()
            _state.value = _state.value.copy(message = "Diagnostic history cleared.")
        }
    }

    /**
     * Reads a stored report's JSON and hands it to [onLoaded].
     *
     * Only the newest request may publish. Expanding one history entry and then another used to
     * leave both reads in flight with nothing ordering their completions, so the slower read for
     * the entry the user had moved away from could land last and be rendered inside the entry now
     * open — one stored report shown under another entry's heading. The ticket is taken before the
     * read starts, so it records the order the taps happened in rather than the order the disk
     * answered in, and a superseded read is simply dropped.
     */
    fun loadRawReport(id: String, onLoaded: (String?) -> Unit) {
        val request = rawReportRequests.begin()
        viewModelScope.launch {
            val text = container.history.loadRaw(id)
            if (rawReportRequests.isCurrent(request)) onLoaded(text)
        }
    }

    /**
     * Re-derives the parts of the report that depend on the diagnostic session.
     *
     * Only `parts` and `scores` read it. The integrity analysis reads the snapshot alone, and the
     * age estimate reads the snapshot, the reference and the purchase date, so neither can differ
     * from the previous report: a new snapshot only ever arrives through [scan], which builds a
     * whole new report instead of rebuilding this one, and the purchase date only changes through
     * [setPurchaseDate]. Both are reused rather than recomputed, which is what this method already
     * did for `authenticity`. Recomputing them accounted for ~260 µs of the ~500 µs a rebuild took
     * on the JVM fixture, on the main thread, for every diagnostic result the user records.
     *
     * [recomputeAge] is set by the one caller that does change an age input.
     */
    private fun rebuildReport(recomputeAge: Boolean = false) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val previous = current.report
        val reference = current.reference
        val parts = container.partsProvider.assess(snapshot, reference, current.session)
        val age = if (recomputeAge || previous == null) {
            container.ageEstimator.estimate(snapshot, reference, container.purchaseDateMillis)
        } else {
            previous.age
        }
        val integrity = previous?.integrity ?: container.integrityAnalyzer.analyze(snapshot)
        val scores = container.scoreEngine.score(snapshot, parts, integrity, current.session)
        val report = DeviceReport(
            snapshot = snapshot,
            authenticity = previous?.authenticity
                ?: container.authenticityEngine.analyze(snapshot, reference),
            parts = parts,
            age = age,
            integrity = integrity,
            scores = scores,
            session = current.session,
            generatedAtMillis = System.currentTimeMillis(),
            appVersion = snapshot.appVersion,
            redacted = current.redacted
        )
        _state.value = current.copy(report = report)
    }
}
