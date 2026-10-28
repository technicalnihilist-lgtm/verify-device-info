package com.verifydeviceinfo

import android.app.Application
import android.content.Context
import com.verifydeviceinfo.data.android.AndroidDeviceInformationProvider
import com.verifydeviceinfo.data.android.AndroidHardwareDiagnosticsEngine
import com.verifydeviceinfo.data.android.AndroidPermissionManager
import com.verifydeviceinfo.data.android.AssetReferenceRepository
import com.verifydeviceinfo.data.android.FileDiagnosticHistory
import com.verifydeviceinfo.data.android.MockDeviceProvider
import com.verifydeviceinfo.data.android.PdfReportGenerator
import com.verifydeviceinfo.domain.AuthenticityAnalysisEngine
import com.verifydeviceinfo.domain.DeviceAgeEstimator
import com.verifydeviceinfo.domain.DeviceInformationProvider
import com.verifydeviceinfo.domain.EvidenceAndConfidenceEngine
import com.verifydeviceinfo.domain.HardwareDiagnosticsEngine
import com.verifydeviceinfo.domain.LocalDiagnosticHistory
import com.verifydeviceinfo.domain.PartsVerificationProvider
import com.verifydeviceinfo.domain.ReferenceRepository
import com.verifydeviceinfo.domain.ReportGenerator
import com.verifydeviceinfo.domain.SystemIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine

/**
 * Manual dependency graph. Deliberately plain: no annotation processor, no reflection-based DI,
 * so the wiring is readable and the build stays fast.
 */
class AppContainer(private val application: Application) {

    private val preferences =
        application.getSharedPreferences("verify_device_info", Context.MODE_PRIVATE)

    val referenceRepository: ReferenceRepository = AssetReferenceRepository(application)
    val permissionManager = AndroidPermissionManager(application)
    val deviceInformationProvider: DeviceInformationProvider = AndroidDeviceInformationProvider(application)
    val diagnosticsEngine: HardwareDiagnosticsEngine =
        AndroidHardwareDiagnosticsEngine(application, permissionManager)
    val authenticityEngine: AuthenticityAnalysisEngine = DefaultAuthenticityEngine()
    val partsProvider: PartsVerificationProvider = DefaultPartsProvider()
    val ageEstimator: DeviceAgeEstimator = DefaultAgeEstimator()
    val integrityAnalyzer: SystemIntegrityAnalyzer = DefaultIntegrityAnalyzer()
    val scoreEngine: EvidenceAndConfidenceEngine = DefaultScoreEngine()
    val reportGenerator: ReportGenerator = PdfReportGenerator(application)
    val history: LocalDiagnosticHistory = FileDiagnosticHistory(application)

    /** Only reachable in debug builds, and always labelled as mock data in the UI. */
    val mockProvider: DeviceInformationProvider? =
        if (BuildConfig.DEBUG) MockDeviceProvider() else null

    var purchaseDateMillis: Long?
        get() = preferences.getLong("purchase_date", 0L).takeIf { it > 0L }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove("purchase_date") else putLong("purchase_date", value)
            }.apply()
        }

    var redactByDefault: Boolean
        get() = preferences.getBoolean("redact", true)
        set(value) = preferences.edit().putBoolean("redact", value).apply()
}
