package com.verifydeviceinfo.domain.report

import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.util.Formatters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Machine-readable report export. Pure Kotlin (kotlinx.serialization), so the same encoder can be
 * reused by a future iOS build.
 *
 * Redaction is on by default: serial numbers, Bluetooth names, signing-certificate digests and
 * other identifiers are replaced rather than exported.
 */
object ReportJson {

    private val pretty = Json { prettyPrint = true }

    fun encode(report: DeviceReport, redacted: Boolean): String =
        pretty.encodeToString(JsonElement.serializer(), build(report, redacted))

    private fun JsonObjectBuilder.putStr(key: String, value: String?) {
        put(key, if (value == null) JsonNull else JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putIntOrNull(key: String, value: Int?) {
        put(key, if (value == null) JsonNull else JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putLongOrNull(key: String, value: Long?) {
        put(key, if (value == null) JsonNull else JsonPrimitive(value))
    }

    private fun build(report: DeviceReport, redacted: Boolean): JsonObject = buildJsonObject {
        put("reportFormatVersion", 1)
        put("app", "Verify Device Info")
        put("appVersion", report.appVersion)
        put("generatedAt", Formatters.utcDate(report.generatedAtMillis))
        put("generatedAtMillis", report.generatedAtMillis)
        put("redacted", redacted)
        put("mockData", report.snapshot.isMock)
        put(
            "disclaimer",
            "This report records what the device reported about itself. It cannot prove that physical " +
                "components are genuine, and a missing value is not evidence of tampering."
        )

        putJsonObject("platform") {
            put("os", report.snapshot.platform.name)
            put("collectedAt", Formatters.utcDate(report.snapshot.collectedAtMillis))
        }

        putJsonObject("identity") {
            put("manufacturer", report.snapshot.identity.manufacturer)
            put("brand", report.snapshot.identity.brand)
            put("model", report.snapshot.identity.model)
            putStr("marketingName", report.snapshot.identity.marketingName)
            put("device", report.snapshot.identity.device)
            put("product", report.snapshot.identity.product)
            put("hardware", report.snapshot.identity.hardware)
            put("board", report.snapshot.identity.board)
            put("fingerprint", report.snapshot.identity.fingerprint)
            put("buildId", report.snapshot.identity.buildId)
            put("buildTags", report.snapshot.identity.buildTags)
            put("buildType", report.snapshot.identity.buildType)
            putStr("socManufacturer", report.snapshot.identity.socManufacturer)
            putStr("socModel", report.snapshot.identity.socModel)
            putStr(
                "serial",
                if (redacted) "REDACTED" else report.snapshot.identity.serial
            )
        }

        putJsonObject("operatingSystem") {
            put("release", report.snapshot.software.androidRelease)
            put("apiLevel", report.snapshot.software.apiLevel)
            putStr("securityPatch", report.snapshot.software.securityPatch)
            putStr("kernel", report.snapshot.software.kernelVersion)
            put("uptime", Formatters.uptime(report.snapshot.software.uptimeMillis))
        }

        putJsonArray("specifications") {
            report.snapshot.fields.forEach { field ->
                add(
                    buildJsonObject {
                        put("key", field.key)
                        put("label", field.label)
                        put("group", field.group.name)
                        put(
                            "value",
                            if (redacted && field.sensitive) "REDACTED" else field.value
                        )
                        put("status", field.status.name)
                        put("confidence", field.confidence.name)
                        put("verification", field.verification.name)
                        put("source", field.source)
                        putStr("note", field.note)
                    }
                )
            }
        }

        putJsonObject("authenticity") {
            put("headline", report.authenticity.headline)
            put("riskLevel", report.authenticity.riskLevel.name)
            put("referenceSource", report.authenticity.referenceSourceSummary)
            putJsonArray("findings") {
                report.authenticity.findings.forEach { finding ->
                    add(
                        buildJsonObject {
                            put("id", finding.id)
                            put("title", finding.title)
                            put("status", finding.status.name)
                            put("risk", finding.risk.name)
                            put("confidence", finding.confidence.name)
                            put("explanation", finding.explanation)
                            putJsonArray("evidence") {
                                finding.evidence.forEach { evidence ->
                                    add(
                                        buildJsonObject {
                                            put("description", evidence.description)
                                            put("source", evidence.source.name)
                                            putStr("detail", evidence.detail)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        putJsonObject("parts") {
            putJsonArray("components") {
                report.parts.components.forEach { component ->
                    add(
                        buildJsonObject {
                            put("component", component.component)
                            put("state", component.state.name)
                            put("confidence", component.confidence.name)
                            put("explanation", component.explanation)
                            putJsonArray("evidence") {
                                component.evidence.forEach { evidence ->
                                    add(
                                        buildJsonObject {
                                            put("description", evidence.description)
                                            put("source", evidence.source.name)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
            putJsonArray("limitations") { report.parts.limitations.forEach { add(it) } }
        }

        putJsonObject("age") {
            putStr("modelLaunchDate", report.age.modelLaunchDate)
            put("modelLaunchAge", Formatters.duration(report.age.modelLaunchAgeDays))
            putStr("firmwareBuildDate", report.age.firmwareBuildDate)
            put("firmwareBuildAge", Formatters.duration(report.age.firmwareBuildAgeDays))
            put("ownershipAge", Formatters.duration(report.age.ownershipAgeDays))
            put("estimatedUsageAge", Formatters.duration(report.age.estimatedUsageAgeDays))
            putStr("securityPatchDate", report.age.securityPatchDate)
            putLongOrNull("daysSinceSecurityPatch", report.age.daysSinceSecurityPatch)
            put("confidence", report.age.confidence.name)
            putJsonArray("notes") { report.age.notes.forEach { add(it) } }
        }

        putJsonObject("integrity") {
            put("overall", report.integrity.overallStatus.name)
            put("summary", report.integrity.summary)
            putJsonArray("checks") {
                report.integrity.checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("id", check.id)
                            put("title", check.title)
                            put("status", check.status.name)
                            put("explanation", check.explanation)
                        }
                    )
                }
            }
        }

        putJsonObject("scores") {
            report.scores.scores.forEach { score ->
                putJsonObject(score.name) {
                    putIntOrNull("score", score.score)
                    put("band", score.band.name)
                    put("coveragePercent", score.coveragePercent)
                    put("summary", score.summary)
                    putJsonArray("inputs") {
                        score.inputs.forEach { input ->
                            add(
                                buildJsonObject {
                                    put("label", input.label)
                                    put("value", input.value)
                                    put("weight", input.weight)
                                    putStr("note", input.note)
                                }
                            )
                        }
                    }
                }
            }
        }

        putJsonArray("diagnostics") {
            report.session.results.forEach { result ->
                add(
                    buildJsonObject {
                        put("testId", result.testId)
                        put("title", result.title)
                        put("status", result.status.name)
                        putStr("measured", result.measured)
                        putStr("note", result.note)
                        put("timestamp", Formatters.utcDate(result.timestampMillis))
                    }
                )
            }
        }

        putJsonArray("unknownOrInaccessible") {
            report.snapshot.fields
                .filter {
                    it.status == DiagnosticStatus.NOT_SUPPORTED ||
                        it.status == DiagnosticStatus.UNVERIFIED ||
                        it.status == DiagnosticStatus.PERMISSION_REQUIRED
                }
                .forEach { field ->
                    add(
                        buildJsonObject {
                            put("key", field.key)
                            put("label", field.label)
                            put("status", field.status.name)
                            putStr("reason", field.note)
                        }
                    )
                }
        }

        if (report.snapshot.collectionErrors.isNotEmpty()) {
            putJsonArray("collectionErrors") { report.snapshot.collectionErrors.forEach { add(it) } }
        }
    }
}
