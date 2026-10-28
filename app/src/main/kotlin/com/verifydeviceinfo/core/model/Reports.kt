package com.verifydeviceinfo.core.model

/** Curated manufacturer/reference specification used as the comparison baseline. */
data class ReferenceDevice(
    val modelPatterns: List<String>,
    val brand: String,
    val marketingName: String,
    val soc: String,
    val socManufacturer: String,
    val releaseDate: String,
    val ramOptionsGb: List<Int>,
    val storageOptionsGb: List<Int>,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val rearCameraCount: Int,
    val frontCameraCount: Int,
    val mainCameraMegapixels: Double,
    val batteryMah: Int,
    val expectedSensors: List<String>,
    val sourceUrl: String,
    val sourceDate: String,
    val sourceName: String
)

data class ReferenceMatch(
    val device: ReferenceDevice,
    val matchedPattern: String
)

data class Finding(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val risk: RiskLevel,
    val explanation: String,
    val evidence: List<Evidence>,
    val confidence: Confidence
)

data class AuthenticityReport(
    val headline: String,
    val riskLevel: RiskLevel,
    val findings: List<Finding>,
    val reference: ReferenceMatch?,
    val referenceSourceSummary: String
)

data class ComponentAssessment(
    val component: String,
    val state: ComponentState,
    val explanation: String,
    val evidence: List<Evidence>,
    val confidence: Confidence
)

data class PartsReport(
    val components: List<ComponentAssessment>,
    val limitations: List<String>
)

data class AgeReport(
    val modelLaunchDate: String?,
    val modelLaunchAgeDays: Long?,
    val firmwareBuildDate: String?,
    val firmwareBuildAgeDays: Long?,
    val ownershipAgeDays: Long?,
    val purchaseDateUsed: String?,
    val estimatedUsageAgeDays: Long?,
    val securityPatchDate: String?,
    val daysSinceSecurityPatch: Long?,
    val notes: List<String>,
    val confidence: Confidence
)

data class IntegrityCheck(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val explanation: String,
    val evidence: List<Evidence>
)

data class IntegrityReport(
    val checks: List<IntegrityCheck>,
    val overallStatus: DiagnosticStatus,
    val summary: String
)

data class ScoreInput(val label: String, val value: String, val weight: Float, val note: String? = null)

data class ScoreResult(
    val name: String,
    val score: Int?,
    val band: ScoreBand,
    val inputs: List<ScoreInput>,
    val coveragePercent: Int,
    val summary: String
)

data class HealthScores(val scores: List<ScoreResult>) {
    fun byName(name: String): ScoreResult? = scores.firstOrNull { it.name == name }
}

data class DiagnosticTestDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val permission: AppPermission?,
    val automated: Boolean,
    val requiresManualConfirmation: Boolean
)

data class DiagnosticResult(
    val testId: String,
    val title: String,
    val status: DiagnosticStatus,
    val measured: String? = null,
    val note: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DiagnosticSession(val results: List<DiagnosticResult> = emptyList()) {
    fun resultFor(testId: String): DiagnosticResult? = results.firstOrNull { it.testId == testId }
    fun with(result: DiagnosticResult): DiagnosticSession =
        DiagnosticSession(results.filterNot { it.testId == result.testId } + result)
}

data class DeviceReport(
    val snapshot: DeviceSnapshot,
    val authenticity: AuthenticityReport,
    val parts: PartsReport,
    val age: AgeReport,
    val integrity: IntegrityReport,
    val scores: HealthScores,
    val session: DiagnosticSession,
    val generatedAtMillis: Long,
    val appVersion: String,
    val redacted: Boolean
)

data class HistoryEntry(
    val id: String,
    val timestampMillis: Long,
    val modelLabel: String,
    val riskLevel: RiskLevel,
    val hardwareScore: Int?,
    val authenticityScore: Int?,
    val issueCount: Int,
    val reportPath: String?
)
