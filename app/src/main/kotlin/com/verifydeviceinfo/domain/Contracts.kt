package com.verifydeviceinfo.domain

import com.verifydeviceinfo.core.model.AgeReport
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.AuthenticityReport
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticTestDescriptor
import com.verifydeviceinfo.core.model.HealthScores
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.core.model.IntegrityReport
import com.verifydeviceinfo.core.model.PartsReport
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.core.model.ScanProgress
import java.io.File

/**
 * Every collector implements this. iOS ships its own implementation of the same contract;
 * Android facts are never rendered as if they came from an iPhone.
 */
interface DeviceInformationProvider {
    val platform: Platform
    suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot
}

/** Compares collected facts against curated manufacturer reference data. */
interface AuthenticityAnalysisEngine {
    fun analyze(snapshot: DeviceSnapshot, reference: ReferenceMatch?): AuthenticityReport
}

/** Produces the five-state component assessment. Never invents serials or service records. */
interface PartsVerificationProvider {
    fun assess(snapshot: DeviceSnapshot, reference: ReferenceMatch?, session: DiagnosticSession): PartsReport
}

/** Estimates age from several independent, separately reported sources. */
interface DeviceAgeEstimator {
    fun estimate(snapshot: DeviceSnapshot, reference: ReferenceMatch?, purchaseDateMillis: Long?): AgeReport
}

/** Root / bootloader / integrity indicators. Read-only; nothing is bypassed. */
interface SystemIntegrityAnalyzer {
    fun analyze(snapshot: DeviceSnapshot): IntegrityReport
}

/** Turns evidence into explainable scores. Unknown data lowers coverage, never the score. */
interface EvidenceAndConfidenceEngine {
    fun score(
        snapshot: DeviceSnapshot,
        parts: PartsReport,
        integrity: IntegrityReport,
        session: DiagnosticSession
    ): HealthScores
}

/** Interactive and automatic hardware tests. */
interface HardwareDiagnosticsEngine {
    fun availableTests(): List<DiagnosticTestDescriptor>
    suspend fun runAutomatedChecks(): List<com.verifydeviceinfo.core.model.DiagnosticResult>
}

interface ReportGenerator {
    suspend fun toJson(report: DeviceReport): String
    suspend fun toPdf(report: DeviceReport): File
}

interface PermissionManager {
    fun state(permission: AppPermission): PermissionState
    fun isGranted(permission: AppPermission): Boolean
    suspend fun request(permission: AppPermission): PermissionState
}

interface LocalDiagnosticHistory {
    suspend fun save(report: DeviceReport, reportPath: String?): HistoryEntry
    suspend fun list(): List<HistoryEntry>
    suspend fun loadRaw(reportId: String): String?
    suspend fun clear()
}

/** Offline-first reference data. Bundled with the app; optional online refresh is opt-in. */
interface ReferenceRepository {
    fun match(model: String, brand: String, device: String): ReferenceMatch?
    fun all(): List<ReferenceDevice>
    fun sourceSummary(): String
}
