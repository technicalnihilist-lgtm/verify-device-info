package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.HealthScores
import com.verifydeviceinfo.core.model.IntegrityReport
import com.verifydeviceinfo.core.model.PartsReport
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.core.model.ScoreInput
import com.verifydeviceinfo.core.model.ScoreResult
import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.domain.EvidenceAndConfidenceEngine
import kotlin.math.roundToInt

/**
 * Turns evidence into explainable scores.
 *
 * Two rules keep this honest:
 *  1. A missing value is removed from both numerator and denominator, and lowers *coverage*.
 *     It never counts as a failure and never counts as a pass.
 *  2. If coverage is below 30%, the score is withheld (null) and reported as UNKNOWN.
 *
 * The app deliberately does not collapse these into one headline number.
 */
class DefaultScoreEngine : EvidenceAndConfidenceEngine {

    private class Accumulator {
        val inputs = mutableListOf<ScoreInput>()
        private var weighted = 0.0
        private var availableWeight = 0.0
        private var totalWeight = 0.0

        fun add(label: String, weight: Float, value: Int?, valueText: String, note: String? = null) {
            totalWeight += weight
            if (value == null) {
                inputs += ScoreInput(label, valueText, weight, note ?: "Not available on this device")
                return
            }
            availableWeight += weight
            weighted += value.coerceIn(0, 100) * weight
            inputs += ScoreInput(label, valueText, weight, note)
        }

        fun build(name: String, summary: String): ScoreResult {
            if (totalWeight == 0.0) {
                return ScoreResult(name, null, ScoreBand.UNKNOWN, inputs, 0, "No inputs were available.")
            }
            val coverage = (availableWeight / totalWeight * 100).roundToInt()
            if (coverage < 30) {
                return ScoreResult(
                    name, null, ScoreBand.UNKNOWN, inputs, coverage,
                    "Insufficient data ($coverage% of inputs available). A score is withheld rather than " +
                        "guessed. $summary"
                )
            }
            val score = (weighted / availableWeight).roundToInt()
            val band = when {
                score >= 80 -> ScoreBand.GOOD
                score >= 55 -> ScoreBand.FAIR
                else -> ScoreBand.POOR
            }
            return ScoreResult(name, score, band, inputs, coverage, summary)
        }
    }

    override fun score(
        snapshot: DeviceSnapshot,
        parts: PartsReport,
        integrity: IntegrityReport,
        session: DiagnosticSession
    ): HealthScores {
        val scores = mutableListOf<ScoreResult>()
        val hardware = snapshot.hardware
        val battery = snapshot.battery
        val software = snapshot.software

        // ---------------- Hardware health ----------------
        run {
            val acc = Accumulator()
            val functional = session.results.filter {
                it.status == DiagnosticStatus.PASS || it.status == DiagnosticStatus.FAILED
            }
            if (functional.isNotEmpty()) {
                val passed = functional.count { it.status == DiagnosticStatus.PASS }
                acc.add(
                    "Interactive tests passed",
                    3f,
                    (passed * 100 / functional.size),
                    "$passed of ${functional.size}",
                    "Only tests the user actually ran are counted."
                )
            }
            acc.add(
                "Cameras enumerated",
                2f,
                if (hardware.cameras.isEmpty()) 0 else 100,
                "${hardware.cameras.size} cameras"
            )
            acc.add(
                "Sensors enumerated",
                2f,
                when {
                    hardware.sensors.isEmpty() -> 0
                    hardware.sensors.size < 4 -> 50
                    else -> 100
                },
                "${hardware.sensors.size} sensors"
            )
            acc.add(
                "Biometric hardware",
                1f,
                if (hardware.biometricAnyAvailable) 100 else 0,
                if (hardware.biometricAnyAvailable) "available" else "not enrolled/available"
            )
            acc.add(
                "Audio output",
                1f,
                if (hardware.speakerAvailable) 100 else 0,
                if (hardware.speakerAvailable) "available" else "not reported"
            )
            acc.add(
                "Vibration motor",
                1f,
                if (hardware.vibratorAvailable) 100 else 0,
                if (hardware.vibratorAvailable) "available" else "not reported"
            )
            acc.add(
                "Free storage",
                1f,
                if (hardware.storageTotalBytes > 0) {
                    (hardware.storageAvailableBytes * 100 / hardware.storageTotalBytes).toInt()
                } else null,
                Formatters.bytes(hardware.storageAvailableBytes) + " free"
            )
            scores += acc.build(
                "Hardware health",
                "Measures whether the hardware this app can reach responds. It cannot see inside a " +
                    "component, so a perfect score is not a clean bill of health."
            )
        }

        // ---------------- Software and security ----------------
        run {
            val acc = Accumulator()
            val patchAge = snapshot.software.securityPatch?.let { sp ->
                try {
                    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    cal.clear()
                    cal.set(sp.substring(0, 4).toInt(), sp.substring(5, 7).toInt() - 1, 1)
                    Formatters.daysBetween(cal.timeInMillis, snapshot.collectedAtMillis)
                } catch (e: Exception) {
                    null
                }
            }
            acc.add(
                "Security patch age",
                3f,
                when {
                    patchAge == null -> null
                    patchAge <= 180 -> 100
                    patchAge <= 365 -> 80
                    patchAge <= 730 -> 50
                    else -> 20
                },
                snapshot.software.securityPatch ?: "unavailable",
                patchAge?.let { "Last patched ${Formatters.duration(it)} ago" }
            )
            acc.add(
                "Root indicators",
                3f,
                if (snapshot.security.rootIndicators.isEmpty()) 100 else 30,
                "${snapshot.security.rootIndicators.size} indicators"
            )
            acc.add(
                "Bootloader",
                2f,
                when (snapshot.security.bootloaderUnlocked) {
                    false -> 100
                    true -> 40
                    null -> null
                },
                when (snapshot.security.bootloaderUnlocked) {
                    false -> "locked"
                    true -> "unlocked"
                    null -> "unknown"
                }
            )
            acc.add(
                "Storage encryption",
                2f,
                if (snapshot.security.encryptionState.contains("encrypted", true)) 100 else 40,
                snapshot.security.encryptionState
            )
            acc.add(
                "Debuggable build",
                1f,
                if (snapshot.security.debuggable) 40 else 100,
                if (snapshot.security.debuggable) "yes" else "no"
            )
            acc.add(
                "Integrity checks",
                2f,
                when (integrity.overallStatus) {
                    DiagnosticStatus.PASS -> 100
                    DiagnosticStatus.WARNING -> 65
                    DiagnosticStatus.FAILED -> 30
                    else -> null
                },
                integrity.overallStatus.name
            )
            acc.add(
                "Android version",
                1f,
                if (software.apiLevel >= 31) 100 else if (software.apiLevel >= 29) 70 else 40,
                "API ${software.apiLevel}"
            )
            scores += acc.build(
                "Software and security",
                "Weighted towards patch recency, root state and boot integrity. A low score means the " +
                    "software is risky, not that the phone is fake."
            )
        }

        // ---------------- Component authenticity confidence ----------------
        run {
            val acc = Accumulator()
            val verified = parts.components.count { it.state == ComponentState.ORIGINALITY_VERIFIED }
            val anomalies = parts.components.count {
                it.state == ComponentState.POSSIBLE_REPLACEMENT || it.state == ComponentState.POSSIBLE_COUNTERFEIT
            }
            val inspected = parts.components.count { it.state != ComponentState.UNABLE_TO_INSPECT }
            val total = parts.components.size
            acc.add(
                "Components with inspectable evidence",
                2f,
                if (total == 0) null else inspected * 100 / total,
                "$inspected of $total"
            )
            acc.add(
                "Components with an anomaly",
                3f,
                if (inspected == 0) null else ((inspected - anomalies) * 100 / inspected),
                "$anomalies of $inspected",
                "An anomaly is an indicator, not a verdict."
            )
            acc.add(
                "Components verified by an official source",
                3f,
                if (total == 0) null else verified * 100 / total,
                "$verified of $total",
                "Android exposes no official part-authentication API, so this is normally zero."
            )
            scores += acc.build(
                "Component authenticity confidence",
                "How much of this device's originality could actually be evidenced. A low score here " +
                    "usually reflects limited platform access, not a suspicious device."
            )
        }

        // ---------------- Battery condition ----------------
        run {
            val acc = Accumulator()
            acc.add(
                "Reported health",
                3f,
                when (battery.healthCode) {
                    2 -> 100
                    3, 4, 5, 6, 7 -> 45
                    else -> null
                },
                battery.health
            )
            acc.add(
                "Estimated capacity",
                3f,
                battery.healthPercent,
                battery.healthPercent?.let { "$it% of design" } ?: "not measurable",
                "Derived from the charge counter at full charge versus the design capacity."
            )
            acc.add(
                "Temperature",
                1f,
                battery.temperatureCelsius?.let { t ->
                    when {
                        t in 0f..40f -> 100
                        t in 40f..45f -> 70
                        t > 45f -> 30
                        else -> 50
                    }
                },
                battery.temperatureCelsius?.let { "$it °C" } ?: "unavailable"
            )
            acc.add(
                "Charge cycles",
                2f,
                battery.cycleCount?.let { c ->
                    when {
                        c < 300 -> 100
                        c < 600 -> 80
                        c < 900 -> 55
                        else -> 30
                    }
                },
                battery.cycleCount?.toString() ?: "not exposed"
            )
            scores += acc.build(
                "Battery condition",
                "Uses the health code, temperature and — where the platform exposes them — cycle count " +
                    "and charge counter. Many devices report only the health code."
            )
        }

        // ---------------- Display and sensors ----------------
        run {
            val acc = Accumulator()
            val display = hardware.display
            acc.add(
                "Display configuration read",
                2f,
                if (display != null && display.widthPx > 0) 100 else null,
                display?.let { "${it.widthPx}x${it.heightPx}" } ?: "unavailable"
            )
            acc.add(
                "Refresh rate",
                1f,
                display?.let { if (it.refreshRateHz >= 60f) 100 else 60 },
                display?.let { "${it.refreshRateHz} Hz" } ?: "unavailable"
            )
            val displayTests = session.results.filter { it.testId.startsWith("display") || it.testId.startsWith("touch") }
            val displayFailed = displayTests.count { it.status == DiagnosticStatus.FAILED }
            acc.add(
                "Display and touch tests",
                3f,
                if (displayTests.isEmpty()) null else ((displayTests.size - displayFailed) * 100 / displayTests.size),
                if (displayTests.isEmpty()) "not run" else "${displayTests.size - displayFailed}/${displayTests.size} passed"
            )
            val sensorTests = session.results.filter { it.testId.startsWith("sensor") }
            val sensorFailed = sensorTests.count { it.status == DiagnosticStatus.FAILED }
            acc.add(
                "Sensor tests",
                2f,
                if (sensorTests.isEmpty()) null else ((sensorTests.size - sensorFailed) * 100 / sensorTests.size),
                if (sensorTests.isEmpty()) "not run" else "${sensorTests.size - sensorFailed}/${sensorTests.size} passed"
            )
            acc.add(
                "Sensor count",
                1f,
                when {
                    hardware.sensors.isEmpty() -> null
                    hardware.sensors.size < 4 -> 50
                    else -> 100
                },
                "${hardware.sensors.size} sensors"
            )
            scores += acc.build(
                "Display and sensors",
                "Combines the reported display configuration with the interactive tests you ran. Tests you " +
                    "have not run are excluded, not assumed to pass."
            )
        }

        // ---------------- Performance ----------------
        run {
            val acc = Accumulator()
            acc.add(
                "Free RAM",
                3f,
                if (hardware.ramTotalBytes > 0) {
                    (hardware.ramAvailableBytes * 100 / hardware.ramTotalBytes).toInt()
                } else null,
                Formatters.bytes(hardware.ramAvailableBytes) + " of " + Formatters.bytes(hardware.ramTotalBytes)
            )
            acc.add(
                "Free storage",
                2f,
                if (hardware.storageTotalBytes > 0) {
                    (hardware.storageAvailableBytes * 100 / hardware.storageTotalBytes).toInt()
                } else null,
                Formatters.bytes(hardware.storageAvailableBytes) + " free"
            )
            acc.add(
                "Thermal status",
                2f,
                when {
                    !software.thermalStatusSupported -> null
                    software.thermalStatus.contains("NONE", true) -> 100
                    software.thermalStatus.contains("LIGHT", true) -> 85
                    software.thermalStatus.contains("MODERATE", true) -> 65
                    software.thermalStatus.contains("SEVERE", true) -> 35
                    else -> 20
                },
                software.thermalStatus
            )
            acc.add(
                "Low-RAM device flag",
                1f,
                if (hardware.isLowRamDevice) 50 else 100,
                if (hardware.isLowRamDevice) "yes" else "no"
            )
            acc.add(
                "CPU cores",
                1f,
                if (hardware.cpuCores >= 8) 100 else if (hardware.cpuCores >= 6) 80 else 60,
                "${hardware.cpuCores} cores"
            )
            scores += acc.build(
                "Performance",
                "A snapshot of current headroom. Free memory and thermal state change constantly, so this " +
                    "is a point-in-time reading, not a benchmark."
            )
        }

        // ---------------- Repair evidence completeness ----------------
        run {
            val acc = Accumulator()
            val total = parts.components.size
            val inspected = parts.components.count { it.state != ComponentState.UNABLE_TO_INSPECT }
            acc.add(
                "Components inspected",
                4f,
                if (total == 0) null else inspected * 100 / total,
                "$inspected of $total"
            )
            acc.add(
                "Diagnostics completed",
                2f,
                session.results.count { it.status == DiagnosticStatus.PASS || it.status == DiagnosticStatus.FAILED }
                    .let { if (it == 0) null else (it * 20).coerceAtMost(100) },
                "${session.results.size} tests recorded"
            )
            scores += acc.build(
                "Repair evidence completeness",
                "How complete the picture is, not how healthy the device is. A low value means 'we could " +
                    "not look', which is different from 'something is wrong'."
            )
        }

        return HealthScores(scores)
    }
}
