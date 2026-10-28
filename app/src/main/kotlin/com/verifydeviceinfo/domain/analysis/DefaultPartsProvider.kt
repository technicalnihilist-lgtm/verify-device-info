package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.ComponentAssessment
import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.Evidence
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.PartsReport
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.PartsVerificationProvider
import java.util.Locale
import kotlin.math.abs

/**
 * Parts history analysis.
 *
 * Android does not expose component serial numbers, service records or an authenticated
 * parts API to third-party apps. Everything here is therefore an *inference from observable
 * evidence*, and every assessment says so. Functional test failures are reported as faults,
 * never as proof that a part was swapped.
 */
class DefaultPartsProvider : PartsVerificationProvider {

    override fun assess(
        snapshot: DeviceSnapshot,
        reference: ReferenceMatch?,
        session: DiagnosticSession
    ): PartsReport {
        val components = mutableListOf<ComponentAssessment>()
        val hardware = snapshot.hardware
        val battery = snapshot.battery
        val ref = reference?.device

        // ---- Display -------------------------------------------------------
        val display = hardware.display
        components += if (ref != null && display != null && ref.displayWidthPx > 0) {
            val longSide = maxOf(display.widthPx, display.heightPx)
            val refLong = maxOf(ref.displayWidthPx, ref.displayHeightPx)
            val shortSide = minOf(display.widthPx, display.heightPx)
            val refShort = minOf(ref.displayWidthPx, ref.displayHeightPx)
            if (longSide != refLong || shortSide != refShort) {
                ComponentAssessment(
                    component = "Display panel",
                    state = ComponentState.POSSIBLE_REPLACEMENT,
                    explanation = "The reported panel resolution ${shortSide}x${longSide} does not match the " +
                        "published ${refShort}x${refLong} for ${ref.brand} ${ref.marketingName}. Aftermarket " +
                        "panels are frequently lower resolution. Note that the reported size comes from the " +
                        "display configuration, which a service centre can also leave misconfigured.",
                    evidence = listOf(
                        Evidence(
                            "Display resolution ${shortSide}x${longSide} @ ${display.densityDpi} dpi",
                            EvidenceSource.ANDROID_API
                        ),
                        Evidence(
                            "Reference ${refShort}x${refLong} @ ${ref.densityDpi} dpi",
                            EvidenceSource.MANUFACTURER_REFERENCE,
                            ref.sourceUrl
                        )
                    ),
                    confidence = Confidence.MEDIUM
                )
            } else {
                ComponentAssessment(
                    component = "Display panel",
                    state = ComponentState.NO_ANOMALY_UNVERIFIED,
                    explanation = "Resolution and density match the published specification, which is " +
                        "consistent with an original panel. Matching resolution does not prove the panel " +
                        "is genuine — a good copy reports the same numbers.",
                    evidence = listOf(
                        Evidence(
                            "Resolution ${shortSide}x${longSide} matches reference",
                            EvidenceSource.ANDROID_API
                        )
                    ),
                    confidence = Confidence.LOW
                )
            }
        } else {
            unable("Display panel", "No reference resolution is available to compare the panel against, " +
                "or the display configuration could not be read.")
        }

        // ---- Battery -------------------------------------------------------
        val healthCode = battery.healthCode
        val healthPercent = battery.healthPercent
        components += when {
            healthCode != 2 && healthCode > 0 -> ComponentAssessment(
                component = "Battery",
                state = ComponentState.POSSIBLE_REPLACEMENT,
                explanation = "The battery service reports health '${battery.health}'. A non-good health " +
                    "state means the pack no longer meets design specifications, which is typical of an " +
                    "aged or replaced cell. It does not identify the pack's origin.",
                evidence = listOf(
                    Evidence("Battery health code=${battery.healthCode} (${battery.health})", EvidenceSource.ANDROID_API),
                    Evidence("Voltage=${battery.voltageMillivolts} mV, temperature=${battery.temperatureCelsius} C",
                        EvidenceSource.ANDROID_API)
                ),
                confidence = Confidence.MEDIUM
            )
            healthPercent != null && healthPercent < 80 -> ComponentAssessment(
                component = "Battery",
                state = ComponentState.POSSIBLE_REPLACEMENT,
                explanation = "Estimated capacity is about $healthPercent% of the design capacity " +
                    "(${battery.designCapacityMah} mAh). Significant capacity loss is consistent with an " +
                    "aged cell or a lower-capacity replacement pack.",
                evidence = listOf(
                    Evidence(
                        "Charge counter ${battery.chargeCounterMicroAmpHours} uAh at ${battery.percent}%",
                        EvidenceSource.ANDROID_API
                    ),
                    Evidence(
                        "Design capacity ${battery.designCapacityMah} mAh (from PowerProfile)",
                        EvidenceSource.DERIVED
                    )
                ),
                confidence = Confidence.LOW
            )
            else -> ComponentAssessment(
                component = "Battery",
                state = ComponentState.NO_ANOMALY_UNVERIFIED,
                explanation = "The battery reports good health" +
                    (healthPercent?.let { " at roughly $it% of design capacity" } ?: "") +
                    ". Health reporting cannot reveal who manufactured the cell or whether it is original.",
                evidence = listOf(
                    Evidence("health=${battery.health}, technology=${battery.technology ?: "unknown"}",
                        EvidenceSource.ANDROID_API),
                    Evidence(
                        if (battery.cycleCount != null) "Cycle count=${battery.cycleCount}"
                        else "Cycle count not exposed by this Android version",
                        EvidenceSource.ANDROID_API
                    )
                ),
                confidence = Confidence.LOW
            )
        }

        // ---- Cameras -------------------------------------------------------
        components += if (ref != null) {
            val rear = hardware.rearCameraCount
            val front = hardware.frontCameraCount
            if (rear != ref.rearCameraCount || front != ref.frontCameraCount) {
                ComponentAssessment(
                    component = "Camera modules",
                    state = ComponentState.POSSIBLE_REPLACEMENT,
                    explanation = "The camera service enumerates $rear rear and $front front cameras; the " +
                        "reference lists ${ref.rearCameraCount} rear and ${ref.frontCameraCount} front. A " +
                        "missing module can indicate a failed or removed part. Some software builds hide " +
                        "auxiliary cameras from the public camera list, so this is an indicator only.",
                    evidence = listOf(
                        Evidence("Enumerated cameras: rear=$rear front=$front", EvidenceSource.CAMERA_MANAGER),
                        Evidence(
                            "Reference: rear=${ref.rearCameraCount} front=${ref.frontCameraCount}",
                            EvidenceSource.MANUFACTURER_REFERENCE,
                            ref.sourceUrl
                        )
                    ),
                    confidence = Confidence.LOW
                )
            } else {
                ComponentAssessment(
                    component = "Camera modules",
                    state = ComponentState.NO_ANOMALY_UNVERIFIED,
                    explanation = "The number of enumerated cameras matches the reference. Module serials and " +
                        "part numbers are not exposed to apps, so originality cannot be confirmed.",
                    evidence = listOf(
                        Evidence("rear=$rear front=$front", EvidenceSource.CAMERA_MANAGER)
                    ),
                    confidence = Confidence.LOW
                )
            }
        } else {
            unable("Camera modules", "No reference camera configuration is available for this model.")
        }

        // ---- Storage -------------------------------------------------------
        components += if (ref != null && ref.storageOptionsGb.isNotEmpty()) {
            val gb = hardware.storageTotalBytes / 1_073_741_824.0
            if (ref.storageOptionsGb.none { abs(gb - it) <= it * 0.12 }) {
                ComponentAssessment(
                    component = "Storage",
                    state = ComponentState.POSSIBLE_REPLACEMENT,
                    explanation = String.format(
                        Locale.US,
                        "Reported %.1f GB does not match any published capacity (%s). Non-original or " +
                            "reballed storage is one explanation; a regional variant missing from the " +
                            "reference set is another.",
                        gb, ref.storageOptionsGb.joinToString("/")
                    ),
                    evidence = listOf(
                        Evidence("StatFs total=${hardware.storageTotalBytes} bytes", EvidenceSource.ANDROID_API)
                    ),
                    confidence = Confidence.LOW
                )
            } else {
                ComponentAssessment(
                    component = "Storage",
                    state = ComponentState.NO_ANOMALY_UNVERIFIED,
                    explanation = "Capacity matches a published option. Storage part identity is not exposed " +
                        "to apps, so this cannot be verified further.",
                    evidence = listOf(
                        Evidence(String.format(Locale.US, "%.1f GB total", gb), EvidenceSource.ANDROID_API)
                    ),
                    confidence = Confidence.LOW
                )
            }
        } else {
            unable("Storage", "No reference storage options are available for this model.")
        }

        // ---- Sensors and biometrics ---------------------------------------
        val sensorCategories = hardware.sensors.map { it.category.lowercase(Locale.US) }.toSet()
        val missingSensors = ref?.expectedSensors.orEmpty().filter { expected ->
            when (expected.lowercase(Locale.US)) {
                "accelerometer" -> "accelerometer" !in sensorCategories
                "gyroscope" -> "gyroscope" !in sensorCategories
                "magnetometer" -> "magnetometer" !in sensorCategories
                "proximity" -> "proximity" !in sensorCategories
                "light" -> "light" !in sensorCategories
                else -> false
            }
        }
        components += when {
            ref == null -> unable(
                "Sensors and biometrics",
                "No reference sensor list is available to compare against."
            )
            missingSensors.isNotEmpty() -> ComponentAssessment(
                component = "Sensors and biometrics",
                state = ComponentState.POSSIBLE_REPLACEMENT,
                explanation = "Sensors listed for this model were not reported: " +
                    missingSensors.joinToString(", ") + ". A replaced mainboard or a display assembly " +
                    "without its proximity/light sensor is a plausible cause.",
                evidence = listOf(
                    Evidence("Reported sensors: ${sensorCategories.sorted().joinToString(", ")}",
                        EvidenceSource.SENSOR_MANAGER)
                ),
                confidence = Confidence.LOW
            )
            else -> ComponentAssessment(
                component = "Sensors and biometrics",
                state = ComponentState.NO_ANOMALY_UNVERIFIED,
                explanation = "Every sensor in the reference list was reported, and biometric hardware is " +
                    "present. Sensor vendor strings are reported by the firmware and can be spoofed.",
                evidence = listOf(
                    Evidence("${hardware.sensors.size} sensors enumerated", EvidenceSource.SENSOR_MANAGER),
                    Evidence(
                        "Strong biometrics available=${hardware.biometricStrongAvailable}",
                        EvidenceSource.ANDROID_API
                    )
                ),
                confidence = Confidence.LOW
            )
        }

        // ---- Charging port, audio, vibration ------------------------------
        val portTests = listOf("charging", "speaker", "microphone", "vibration")
        val portResults = session.results.filter { it.testId in portTests }
        components += if (portResults.isEmpty()) {
            unable(
                "Charging port, speaker, microphone and vibration",
                "These components have no passive identity signal; run the interactive diagnostics in the " +
                    "Diagnostics tab to test them functionally."
            )
        } else {
            val failed = portResults.filter { it.status == DiagnosticStatus.FAILED }
            val passed = portResults.filter { it.status == DiagnosticStatus.PASS }
            if (failed.isNotEmpty()) {
                ComponentAssessment(
                    component = "Charging port, speaker, microphone and vibration",
                    state = ComponentState.NO_ANOMALY_UNVERIFIED,
                    explanation = "Functional failures were recorded for: " +
                        failed.joinToString(", ") { it.title } +
                        ". A failed function indicates a fault — a dead microphone is not evidence that the " +
                        "microphone was replaced. Treat this as a repair signal, not an originality finding.",
                    evidence = failed.map {
                        Evidence("${it.title}: ${it.status} ${it.measured ?: ""}", EvidenceSource.ANDROID_API)
                    },
                    confidence = Confidence.MEDIUM
                )
            } else {
                ComponentAssessment(
                    component = "Charging port, speaker, microphone and vibration",
                    state = ComponentState.NO_ANOMALY_UNVERIFIED,
                    explanation = "${passed.size} functional test(s) passed. Working components are " +
                        "consistent with original parts but do not prove it.",
                    evidence = passed.map {
                        Evidence("${it.title}: passed", EvidenceSource.ANDROID_API)
                    },
                    confidence = Confidence.LOW
                )
            }
        }

        // ---- Motherboard / device identity --------------------------------
        val identityWarnings = mutableListOf<Evidence>()
        if (!AnalysisSupport.brandsRelated(snapshot.identity.manufacturer, snapshot.identity.brand)) {
            identityWarnings += Evidence(
                "manufacturer=${snapshot.identity.manufacturer} vs brand=${snapshot.identity.brand}",
                EvidenceSource.BUILD_FIELDS
            )
        }
        val fp = AnalysisSupport.parseFingerprint(snapshot.identity.fingerprint)
        if (fp.size >= 3 &&
            AnalysisSupport.normalise(fp[0]) != AnalysisSupport.normalise(snapshot.identity.brand) &&
            !AnalysisSupport.brandsRelated(fp[0], snapshot.identity.brand)
        ) {
            identityWarnings += Evidence(
                "fingerprint brand '${fp[0]}' vs reported brand '${snapshot.identity.brand}'",
                EvidenceSource.BUILD_FIELDS
            )
        }
        val hardwareMismatches = listOf(
            ref != null && snapshot.identity.socModel != null &&
                !AnalysisSupport.socMatches(snapshot.identity.socModel, ref.soc),
            ref != null && hardware.display != null &&
                (maxOf(hardware.display.widthPx, hardware.display.heightPx) !=
                    maxOf(ref.displayWidthPx, ref.displayHeightPx)),
            ref != null && ref.ramOptionsGb.isNotEmpty() &&
                ref.ramOptionsGb.none { abs(hardware.ramTotalBytes / 1_073_741_824.0 - it) <= 0.6 }
        ).count { it }

        components += when {
            identityWarnings.isNotEmpty() && hardwareMismatches >= 2 -> ComponentAssessment(
                component = "Motherboard and device identity",
                state = ComponentState.POSSIBLE_COUNTERFEIT,
                explanation = "Identity strings disagree and $hardwareMismatches independent hardware values " +
                    "differ from the published specification. This combination is the strongest pattern " +
                    "this app can detect, but it is still a pattern — not a confirmation.",
                evidence = identityWarnings + Evidence(
                    "$hardwareMismatches hardware mismatches against the reference",
                    EvidenceSource.MANUFACTURER_REFERENCE
                ),
                confidence = Confidence.MEDIUM
            )
            identityWarnings.isNotEmpty() -> ComponentAssessment(
                component = "Motherboard and device identity",
                state = ComponentState.POSSIBLE_REPLACEMENT,
                explanation = "Identity properties are inconsistent with each other. A mainboard swap or a " +
                    "re-flashed firmware image can both produce this. Review the Authenticity report for the " +
                    "individual findings.",
                evidence = identityWarnings,
                confidence = Confidence.LOW
            )
            else -> ComponentAssessment(
                component = "Motherboard and device identity",
                state = ComponentState.NO_ANOMALY_UNVERIFIED,
                explanation = "Identity properties are mutually consistent. The mainboard's part number and " +
                    "serial are not readable by apps, so originality is unverified.",
                evidence = listOf(
                    Evidence(
                        "fingerprint, brand, product and device agree",
                        EvidenceSource.BUILD_FIELDS
                    )
                ),
                confidence = Confidence.LOW
            )
        }

        return PartsReport(
            components = components,
            limitations = listOf(
                "Android provides no public API for component serial numbers, part numbers or service history.",
                "Samsung, Apple and other manufacturers run proprietary service diagnostics that third-party " +
                    "apps cannot call; this app does not simulate their results.",
                "A replaced part that reports the same specifications as the original is indistinguishable " +
                    "from software alone.",
                "A component that is missing or unreadable is reported as UNINSPECTABLE, never as counterfeit.",
                "Refurbished devices are not counterfeit devices. Refurbishment is a legitimate process and " +
                    "this app does not treat it as fraud."
            )
        )
    }

    private fun unable(component: String, explanation: String) = ComponentAssessment(
        component = component,
        state = ComponentState.UNABLE_TO_INSPECT,
        explanation = explanation,
        evidence = listOf(
            Evidence("No usable evidence available for this component", EvidenceSource.HEURISTIC)
        ),
        confidence = Confidence.NONE
    )
}
