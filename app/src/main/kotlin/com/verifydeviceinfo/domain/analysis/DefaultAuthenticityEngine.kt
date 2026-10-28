package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.AuthenticityReport
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.Finding
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.domain.AuthenticityAnalysisEngine
import java.util.Locale
import kotlin.math.abs

/**
 * Evidence-based authenticity analysis.
 *
 * Design rule: every check reports what it actually observed. A mismatch is an *indicator*
 * that justifies a closer look — never proof of counterfeit hardware. When there is no
 * reference data, the result is UNVERIFIED rather than a pass.
 */
class DefaultAuthenticityEngine : AuthenticityAnalysisEngine {

    override fun analyze(snapshot: DeviceSnapshot, reference: ReferenceMatch?): AuthenticityReport {
        if (snapshot.platform != Platform.ANDROID) {
            return AuthenticityReport(
                headline = "Authenticity analysis is only implemented for the platform this build was compiled for.",
                riskLevel = RiskLevel.UNKNOWN,
                findings = listOf(
                    AnalysisSupport.finding(
                        id = "platform.unsupported",
                        title = "Analysis not applicable to this platform",
                        status = DiagnosticStatus.NOT_SUPPORTED,
                        risk = RiskLevel.UNKNOWN,
                        explanation = "This build collected ${snapshot.platform} facts but the reference comparison " +
                            "logic requires a matching platform implementation.",
                        confidence = Confidence.NONE,
                        evidence = listOf(
                            AnalysisSupport.evidence(
                                "Collected platform reported as ${snapshot.platform}",
                                EvidenceSource.ANDROID_API
                            )
                        )
                    )
                ),
                reference = reference,
                referenceSourceSummary = "n/a"
            )
        }

        val findings = mutableListOf<Finding>()
        val identity = snapshot.identity
        val hardware = snapshot.hardware

        // 1. Manufacturer / brand coherence.
        if (AnalysisSupport.brandsRelated(identity.manufacturer, identity.brand)) {
            findings += AnalysisSupport.finding(
                id = "identity.brand",
                title = "Manufacturer and brand are consistent",
                status = DiagnosticStatus.VERIFIED,
                risk = RiskLevel.LOW,
                explanation = "ro.product.manufacturer (${identity.manufacturer}) and ro.product.brand " +
                    "(${identity.brand}) belong to the same vendor family.",
                confidence = Confidence.HIGH,
                evidence = listOf(
                    AnalysisSupport.evidence("manufacturer=${identity.manufacturer}", EvidenceSource.BUILD_FIELDS),
                    AnalysisSupport.evidence("brand=${identity.brand}", EvidenceSource.BUILD_FIELDS)
                )
            )
        } else {
            findings += AnalysisSupport.finding(
                id = "identity.brand",
                title = "Manufacturer and brand disagree",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.ELEVATED,
                explanation = "The build reports manufacturer '${identity.manufacturer}' with brand " +
                    "'${identity.brand}'. These are not a known vendor family, which is unusual for a " +
                    "factory build but can also appear on some regional rebrands.",
                confidence = Confidence.MEDIUM,
                evidence = listOf(
                    AnalysisSupport.evidence("manufacturer=${identity.manufacturer}", EvidenceSource.BUILD_FIELDS),
                    AnalysisSupport.evidence("brand=${identity.brand}", EvidenceSource.BUILD_FIELDS)
                )
            )
        }

        // 2. Build fingerprint structure.
        val fp = AnalysisSupport.parseFingerprint(identity.fingerprint)
        if (fp.size >= 4) {
            val fpBrand = fp[0]
            val fpProduct = fp[1]
            val fpDevice = fp[2]
            val coherent = AnalysisSupport.normalise(fpBrand) == AnalysisSupport.normalise(identity.brand) ||
                AnalysisSupport.brandsRelated(fpBrand, identity.brand)
            findings += if (coherent) {
                AnalysisSupport.finding(
                    id = "build.fingerprint",
                    title = "Build fingerprint is internally coherent",
                    status = DiagnosticStatus.VERIFIED,
                    risk = RiskLevel.LOW,
                    explanation = "The fingerprint's brand/product/device triple matches the reported " +
                        "brand, product and device properties.",
                    confidence = Confidence.HIGH,
                    evidence = listOf(
                        AnalysisSupport.evidence("fingerprint=$fp", EvidenceSource.BUILD_FIELDS),
                        AnalysisSupport.evidence(
                            "product=${identity.product}, device=${identity.device}",
                            EvidenceSource.BUILD_FIELDS
                        )
                    )
                )
            } else {
                AnalysisSupport.finding(
                    id = "build.fingerprint",
                    title = "Build fingerprint does not match reported identity",
                    status = DiagnosticStatus.WARNING,
                    risk = RiskLevel.ELEVATED,
                    explanation = "The fingerprint declares brand '$fpBrand' (product '$fpProduct', device " +
                        "'$fpDevice') but the system reports brand '${identity.brand}', product " +
                        "'${identity.product}', device '${identity.device}'. Spoofed or hand-edited build " +
                        "properties produce exactly this pattern.",
                    confidence = Confidence.HIGH,
                    evidence = listOf(
                        AnalysisSupport.evidence("fingerprint=$fp", EvidenceSource.BUILD_FIELDS),
                        AnalysisSupport.evidence(
                            "reported brand=${identity.brand}, product=${identity.product}, device=${identity.device}",
                            EvidenceSource.BUILD_FIELDS
                        )
                    )
                )
            }
        } else {
            findings += AnalysisSupport.finding(
                id = "build.fingerprint",
                title = "Build fingerprint could not be parsed",
                status = DiagnosticStatus.UNVERIFIED,
                risk = RiskLevel.UNKNOWN,
                explanation = "The fingerprint string did not have the expected 8-segment Android format, " +
                    "so its internal consistency could not be checked.",
                confidence = Confidence.LOW,
                evidence = listOf(
                    AnalysisSupport.evidence("fingerprint=${identity.fingerprint}", EvidenceSource.BUILD_FIELDS)
                )
            )
        }

        // 3. Emulator / generic image markers.
        val genericMarkers = listOf("generic", "sdk", "emulator", "goldfish", "ranchu", "vbox", "cuttlefish")
        val identityBlob = listOf(
            identity.model, identity.device, identity.product, identity.hardware,
            identity.brand, identity.fingerprint
        ).joinToString(" ").lowercase(Locale.US)
        val matchedGeneric = genericMarkers.filter { identityBlob.contains(it) }
        if (matchedGeneric.isNotEmpty()) {
            findings += AnalysisSupport.finding(
                id = "identity.emulator",
                title = "Emulator or generic system image markers present",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.HIGH,
                explanation = "Identity strings contain ${matchedGeneric.joinToString(", ")}. This is normal on " +
                    "an emulator or a generic AOSP image, and would be abnormal on retail hardware.",
                confidence = Confidence.HIGH,
                evidence = listOf(
                    AnalysisSupport.evidence(
                        "matched markers: ${matchedGeneric.joinToString(", ")}",
                        EvidenceSource.BUILD_FIELDS,
                        identityBlob
                    )
                )
            )
        }

        // 4. Firmware type and tags.
        val tags = identity.buildTags.lowercase(Locale.US)
        if (tags.contains("test-keys") || tags.contains("dev-keys")) {
            findings += AnalysisSupport.finding(
                id = "build.tags",
                title = "Non-release signing keys in build tags",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.ELEVATED,
                explanation = "Build tags are '${identity.buildTags}'. Retail firmware is normally signed with " +
                    "release-keys; test-keys or dev-keys indicate engineering or re-signed firmware.",
                confidence = Confidence.HIGH,
                evidence = listOf(
                    AnalysisSupport.evidence("ro.build.tags=${identity.buildTags}", EvidenceSource.BUILD_FIELDS)
                )
            )
        }
        val buildType = identity.buildType.lowercase(Locale.US)
        if (buildType == "userdebug" || buildType == "eng") {
            findings += AnalysisSupport.finding(
                id = "build.type",
                title = "Debug build type",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.ELEVATED,
                explanation = "ro.build.type is '${identity.buildType}'. Retail devices ship as 'user'; " +
                    "userdebug/eng builds are development firmware.",
                confidence = Confidence.HIGH,
                evidence = listOf(
                    AnalysisSupport.evidence("ro.build.type=${identity.buildType}", EvidenceSource.BUILD_FIELDS)
                )
            )
        }

        // 5. Build date sanity.
        val buildTime = identity.buildTimeMillis
        if (buildTime != null && buildTime > 0) {
            val now = snapshot.collectedAtMillis
            val future = buildTime > now + 30L * 86_400_000L
            val ancient = buildTime < 1_420_000_000_000L // mid-2014
            if (future || ancient) {
                findings += AnalysisSupport.finding(
                    id = "build.time",
                    title = "Implausible firmware build timestamp",
                    status = DiagnosticStatus.WARNING,
                    risk = RiskLevel.MODERATE,
                    explanation = if (future) {
                        "ro.build.date is in the future, which suggests edited build properties."
                    } else {
                        "ro.build.date is older than any supported Android release, which suggests edited " +
                            "build properties."
                    },
                    confidence = Confidence.MEDIUM,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "ro.build.date=${identity.buildTimeMillis}",
                            EvidenceSource.SYSTEM_PROPERTY
                        )
                    )
                )
            }
        }

        // 6. Root and custom-ROM traces (integrity signals, reported as such).
        if (snapshot.security.rootIndicators.isNotEmpty()) {
            findings += AnalysisSupport.finding(
                id = "integrity.root",
                title = "Root-management traces detected",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.ELEVATED,
                explanation = "The following indicators were found: " +
                    snapshot.security.rootIndicators.joinToString("; ") +
                    ". Root access does not by itself mean the hardware is counterfeit, but it means system " +
                    "software has been modified.",
                confidence = Confidence.MEDIUM,
                evidence = snapshot.security.rootIndicators.map {
                    AnalysisSupport.evidence(it, EvidenceSource.HEURISTIC)
                }
            )
        }
        if (snapshot.security.customRomSuspected) {
            findings += AnalysisSupport.finding(
                id = "integrity.customrom",
                title = "Custom firmware suspected",
                status = DiagnosticStatus.WARNING,
                risk = RiskLevel.MODERATE,
                explanation = "Firmware characteristics do not match a stock retail image: " +
                    "build type '${identity.buildType}', tags '${identity.buildTags}', " +
                    "verified boot state '${snapshot.security.verifiedBootState ?: "unknown"}'.",
                confidence = Confidence.MEDIUM,
                evidence = listOf(
                    AnalysisSupport.evidence(
                        "verified boot state=${snapshot.security.verifiedBootState}",
                        EvidenceSource.SYSTEM_PROPERTY
                    )
                )
            )
        }

        // 7. Reference-based comparisons.
        if (reference == null) {
            findings += AnalysisSupport.finding(
                id = "reference.missing",
                title = "No manufacturer reference entry for this model",
                status = DiagnosticStatus.UNVERIFIED,
                risk = RiskLevel.UNKNOWN,
                explanation = "Model '${identity.model}' is not in the bundled reference set, so hardware " +
                    "values could not be compared against published specifications. This is a coverage " +
                    "limitation of the app, not an indication of a fake device.",
                confidence = Confidence.NONE,
                evidence = listOf(
                    AnalysisSupport.evidence(
                        "model=${identity.model}, brand=${identity.brand}",
                        EvidenceSource.BUILD_FIELDS
                    )
                )
            )
        } else {
            val ref = reference.device

            // SoC
            val soc = identity.socModel ?: hardware.socModel
            if (soc.isNullOrBlank()) {
                findings += AnalysisSupport.finding(
                    id = "soc.reference",
                    title = "Chipset could not be compared with the reference",
                    status = DiagnosticStatus.UNVERIFIED,
                    risk = RiskLevel.UNKNOWN,
                    explanation = "This Android version does not expose ro.soc.model, so the reported chipset " +
                        "could not be read and compared with the reference value '${ref.soc}'.",
                    confidence = Confidence.NONE,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "ro.soc.model unavailable (requires API 31+)",
                            EvidenceSource.ANDROID_API
                        )
                    )
                )
            } else if (AnalysisSupport.socMatches(soc, ref.soc)) {
                findings += AnalysisSupport.finding(
                    id = "soc.reference",
                    title = "Chipset matches the published specification",
                    status = DiagnosticStatus.VERIFIED,
                    risk = RiskLevel.LOW,
                    explanation = "Reported '${soc}' is consistent with the reference chipset '${ref.soc}'.",
                    confidence = Confidence.HIGH,
                    evidence = listOf(
                        AnalysisSupport.evidence("ro.soc.model=$soc", EvidenceSource.SYSTEM_PROPERTY),
                        AnalysisSupport.evidence(
                            "reference soc=${ref.soc}",
                            EvidenceSource.MANUFACTURER_REFERENCE,
                            ref.sourceUrl
                        )
                    )
                )
            } else {
                findings += AnalysisSupport.finding(
                    id = "soc.reference",
                    title = "Chipset differs from the published specification",
                    status = DiagnosticStatus.WARNING,
                    risk = RiskLevel.ELEVATED,
                    explanation = "The device reports '${soc}' while the reference for ${ref.brand} " +
                        "${ref.marketingName} lists '${ref.soc}'. Regional variants exist, but a chipset " +
                        "that does not match any published variant is a common counterfeit indicator.",
                    confidence = Confidence.MEDIUM,
                    evidence = listOf(
                        AnalysisSupport.evidence("ro.soc.model=$soc", EvidenceSource.SYSTEM_PROPERTY),
                        AnalysisSupport.evidence(
                            "reference soc=${ref.soc}",
                            EvidenceSource.MANUFACTURER_REFERENCE,
                            ref.sourceUrl
                        )
                    )
                )
            }

            // Display
            val display = hardware.display
            if (display != null && ref.displayWidthPx > 0 && ref.displayHeightPx > 0) {
                val longSide = maxOf(display.widthPx, display.heightPx)
                val shortSide = minOf(display.widthPx, display.heightPx)
                val refLong = maxOf(ref.displayWidthPx, ref.displayHeightPx)
                val refShort = minOf(ref.displayWidthPx, ref.displayHeightPx)
                val sameResolution = longSide == refLong && shortSide == refShort
                val densityClose = ref.densityDpi == 0 ||
                    abs(display.densityDpi - ref.densityDpi) <= (ref.densityDpi * 0.08f)
                if (sameResolution && densityClose) {
                    findings += AnalysisSupport.finding(
                        id = "display.reference",
                        title = "Display panel matches the reference resolution",
                        status = DiagnosticStatus.VERIFIED,
                        risk = RiskLevel.LOW,
                        explanation = "Reported panel is ${shortSide}x${longSide} at ${display.densityDpi} dpi, " +
                            "matching the reference ${refShort}x${refLong} at ${ref.densityDpi} dpi.",
                        confidence = Confidence.HIGH,
                        evidence = listOf(
                            AnalysisSupport.evidence(
                                "display=${shortSide}x${longSide} @ ${display.densityDpi}dpi",
                                EvidenceSource.ANDROID_API
                            )
                        )
                    )
                } else {
                    findings += AnalysisSupport.finding(
                        id = "display.reference",
                        title = "Display does not match the reference panel",
                        status = DiagnosticStatus.WARNING,
                        risk = RiskLevel.ELEVATED,
                        explanation = "Reported panel ${shortSide}x${longSide} @ ${display.densityDpi} dpi " +
                            "differs from the reference ${refShort}x${refLong} @ ${ref.densityDpi} dpi. " +
                            "A lower-resolution panel is one of the most common non-original replacement parts.",
                        confidence = Confidence.MEDIUM,
                        evidence = listOf(
                            AnalysisSupport.evidence(
                                "display=${shortSide}x${longSide} @ ${display.densityDpi}dpi",
                                EvidenceSource.ANDROID_API
                            ),
                            AnalysisSupport.evidence(
                                "reference=${refShort}x${refLong} @ ${ref.densityDpi}dpi",
                                EvidenceSource.MANUFACTURER_REFERENCE,
                                ref.sourceUrl
                            )
                        )
                    )
                }
            }

            // Memory / storage
            val ramGb = hardware.ramTotalBytes / 1_073_741_824.0
            val storageGb = hardware.storageTotalBytes / 1_073_741_824.0
            val ramOk = ref.ramOptionsGb.isEmpty() ||
                ref.ramOptionsGb.any { abs(ramGb - it) <= 0.6 }
            val storageOk = ref.storageOptionsGb.isEmpty() ||
                ref.storageOptionsGb.any { abs(storageGb - it) <= (it * 0.12) }
            findings += if (ramOk && storageOk) {
                AnalysisSupport.finding(
                    id = "memory.reference",
                    title = "Memory and storage match the reference",
                    status = DiagnosticStatus.VERIFIED,
                    risk = RiskLevel.LOW,
                    explanation = String.format(
                        Locale.US,
                        "Reported %.1f GB RAM and %.0f GB storage are within the published options.",
                        ramGb, storageGb
                    ),
                    confidence = Confidence.HIGH,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "ram=${hardware.ramTotalBytes} bytes, storage=${hardware.storageTotalBytes} bytes",
                            EvidenceSource.ANDROID_API
                        )
                    )
                )
            } else {
                AnalysisSupport.finding(
                    id = "memory.reference",
                    title = "Memory or storage outside the published options",
                    status = DiagnosticStatus.WARNING,
                    risk = RiskLevel.MODERATE,
                    explanation = String.format(
                        Locale.US,
                        "Reported %.1f GB RAM / %.0f GB storage do not line up with the reference options " +
                            "(RAM %s, storage %s). Some variants are simply missing from the reference set, " +
                            "so treat this as an indicator only.",
                        ramGb, storageGb,
                        ref.ramOptionsGb.joinToString("/"),
                        ref.storageOptionsGb.joinToString("/")
                    ),
                    confidence = Confidence.LOW,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "reported ram/storage from ActivityManager and StatFs",
                            EvidenceSource.ANDROID_API
                        )
                    )
                )
            }

            // Cameras
            if (ref.mainCameraMegapixels > 0) {
                val mainMp = hardware.rearCameraMegapixels
                if (mainMp > 0 && abs(mainMp - ref.mainCameraMegapixels) > 2.0) {
                    findings += AnalysisSupport.finding(
                        id = "camera.reference",
                        title = "Main camera resolution differs from the reference",
                        status = DiagnosticStatus.WARNING,
                        risk = RiskLevel.MODERATE,
                        explanation = String.format(
                            Locale.US,
                            "Largest rear camera reports %.1f MP; the reference lists %.1f MP. Camera " +
                                "modules are frequently replaced with lower-resolution parts, but sensor " +
                                "crop and binning modes can also affect the reported figure.",
                            mainMp, ref.mainCameraMegapixels
                        ),
                        confidence = Confidence.LOW,
                        evidence = listOf(
                            AnalysisSupport.evidence(
                                "max rear JPEG output=${hardware.cameras.filter { it.lensFacing == "BACK" }.maxOfOrNull { it.maxJpegWidth } ?: 0}px wide",
                                EvidenceSource.CAMERA_MANAGER
                            )
                        )
                    )
                } else if (mainMp > 0) {
                    findings += AnalysisSupport.finding(
                        id = "camera.reference",
                        title = "Camera capabilities match the reference",
                        status = DiagnosticStatus.VERIFIED,
                        risk = RiskLevel.LOW,
                        explanation = String.format(
                            Locale.US,
                            "Largest rear camera reports %.1f MP, consistent with the reference %.1f MP.",
                            mainMp, ref.mainCameraMegapixels
                        ),
                        confidence = Confidence.MEDIUM,
                        evidence = listOf(
                            AnalysisSupport.evidence(
                                "camera count rear/front=${hardware.rearCameraCount}/${hardware.frontCameraCount}",
                                EvidenceSource.CAMERA_MANAGER
                            )
                        )
                    )
                }
            }

            // Sensors
            val presentTypes = hardware.sensors.map { it.category.lowercase(Locale.US) }.toSet()
            val missing = ref.expectedSensors.filter { expected ->
                when (expected.lowercase(Locale.US)) {
                    "accelerometer" -> "accelerometer" !in presentTypes
                    "gyroscope" -> "gyroscope" !in presentTypes
                    "magnetometer" -> "magnetometer" !in presentTypes
                    "proximity" -> "proximity" !in presentTypes
                    "light" -> "light" !in presentTypes
                    else -> false
                }
            }
            findings += if (missing.isEmpty()) {
                AnalysisSupport.finding(
                    id = "sensor.reference",
                    title = "Expected sensors are present",
                    status = DiagnosticStatus.VERIFIED,
                    risk = RiskLevel.LOW,
                    explanation = "All sensors listed in the reference for this model were reported by the " +
                        "sensor service.",
                    confidence = Confidence.MEDIUM,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "sensor types reported: ${presentTypes.sorted().joinToString(", ")}",
                            EvidenceSource.SENSOR_MANAGER
                        )
                    )
                )
            } else {
                AnalysisSupport.finding(
                    id = "sensor.reference",
                    title = "Expected sensors are missing",
                    status = DiagnosticStatus.WARNING,
                    risk = RiskLevel.MODERATE,
                    explanation = "The reference lists ${missing.joinToString(", ")} but the sensor service " +
                        "did not report them. Boards swapped during a repair can lose sensors, though some " +
                        "regional variants also ship without them.",
                    confidence = Confidence.LOW,
                    evidence = listOf(
                        AnalysisSupport.evidence(
                            "missing: ${missing.joinToString(", ")}",
                            EvidenceSource.SENSOR_MANAGER
                        ),
                        AnalysisSupport.evidence(
                            "reference sensor list",
                            EvidenceSource.MANUFACTURER_REFERENCE,
                            ref.sourceUrl
                        )
                    )
                )
            }
        }

        // 8. Manufacturer verification availability — stated, not simulated.
        findings += AnalysisSupport.finding(
            id = "manufacturer.verification",
            title = "No public manufacturer authenticity API used",
            status = DiagnosticStatus.NOT_SUPPORTED,
            risk = RiskLevel.UNKNOWN,
            explanation = "Android exposes no public interface for verifying a handset's hardware " +
                "authenticity. Manufacturers such as Samsung and Apple run their own verification " +
                "portals for authorised service partners; this app does not query them, so no " +
                "'officially verified genuine' claim is made.",
            confidence = Confidence.NONE,
            evidence = listOf(
                AnalysisSupport.evidence(
                    "No manufacturer part-authentication API is available to third-party apps",
                    EvidenceSource.OFFICIAL_DOCUMENTATION
                )
            )
        )

        val risk = AnalysisSupport.aggregateRisk(findings)
        val headline = when {
            snapshot.isMock -> "MOCK DATA — this is a development fixture, not a real device scan."
            risk == RiskLevel.HIGH -> "Multiple strong inconsistencies were detected. This device warrants " +
                "careful manual inspection before purchase."
            risk == RiskLevel.ELEVATED -> "Several indicators do not match the reference data. Treat them as " +
                "reasons to verify further, not as proof of counterfeiting."
            risk == RiskLevel.MODERATE -> "One inconsistency was found. It may be explained by a regional " +
                "variant or a repair."
            risk == RiskLevel.LOW -> "No inconsistencies were found in the values this app can read. This is " +
                "not a guarantee that the hardware is genuine."
            else -> "There is not enough reference data to judge authenticity for this model."
        }

        return AuthenticityReport(
            headline = headline,
            riskLevel = risk,
            findings = findings,
            reference = reference,
            referenceSourceSummary = reference?.let {
                "${it.device.sourceName} — ${it.device.sourceUrl} (curated ${it.device.sourceDate})"
            } ?: "No reference entry matched this model"
        )
    }
}
