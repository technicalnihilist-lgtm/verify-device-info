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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val container = AppContainer(application)

    private val _state = MutableStateFlow(
        UiState(purchaseDateMillis = container.purchaseDateMillis, redacted = container.redactByDefault)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshHistory()
    }

    fun scan(useMockData: Boolean = false) {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                scanning = true, error = null, message = null, exportFile = null,
                progress = ScanProgress(0, 8, "Preparing"), usedMockData = useMockData
            )
            try {
                val provider = if (useMockData) container.mockProvider else container.deviceInformationProvider
                if (provider == null) {
                    _state.value = _state.value.copy(
                        scanning = false, error = "Mock data is only available in debug builds."
                    )
                    return@launch
                }
                val snapshot = provider.collect { progress ->
                    _state.value = _state.value.copy(progress = progress)
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
                container.history.save(report, null)
                _state.value = _state.value.copy(
                    scanning = false,
                    progress = ScanProgress(8, 8, "Complete", finished = true),
                    snapshot = snapshot,
                    reference = reference,
                    report = report,
                    message = if (snapshot.isMock) "Mock scan complete (development fixture)." else null
                )
                refreshHistory()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    scanning = false,
                    error = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun runAutomatedDiagnostics() {
        viewModelScope.launch {
            try {
                val results = container.diagnosticsEngine.runAutomatedChecks()
                var session = _state.value.session
                results.forEach { session = session.with(it) }
                _state.value = _state.value.copy(session = session)
                rebuildReport()
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
        rebuildReport()
    }

    fun setRedacted(redacted: Boolean) {
        container.redactByDefault = redacted
        _state.value = _state.value.copy(redacted = redacted)
        rebuildReport()
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
                val text = container.reportGenerator.toJson(report.copy(redacted = _state.value.redacted))
                val file = File(getApplication<Application>().cacheDir, "verify-device-info-report.json")
                file.writeText(text)
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

    fun loadRawReport(id: String, onLoaded: (String?) -> Unit) {
        viewModelScope.launch { onLoaded(container.history.loadRaw(id)) }
    }

    /** Re-derives everything that depends on the diagnostic session. */
    private fun rebuildReport() {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val reference = current.reference
        val parts = container.partsProvider.assess(snapshot, reference, current.session)
        val age = container.ageEstimator.estimate(snapshot, reference, container.purchaseDateMillis)
        val integrity = container.integrityAnalyzer.analyze(snapshot)
        val scores = container.scoreEngine.score(snapshot, parts, integrity, current.session)
        val report = DeviceReport(
            snapshot = snapshot,
            authenticity = current.report?.authenticity
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
