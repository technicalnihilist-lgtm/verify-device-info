package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import com.verifydeviceinfo.domain.report.ReportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the machine-readable export: the shape a consumer of the JSON can rely on, and the exact
 * scope of redaction.
 *
 * The key sets below are the export's contract, not an implementation detail. A consumer that reads
 * `identity.serial` or `scores.<name>.coveragePercent` must keep working across releases, and
 * `docs/PRIVACY.md` ("Identifiers in exports") promises that the hardware serial, the Bluetooth
 * adapter name and the signing-certificate digest are the only values redaction removes. Both are
 * asserted here, so a key that silently disappears, or a value that starts leaking through a
 * different part of the document, fails the build instead of shipping.
 */
class ReportJsonTest {

    private val parser = Json

    // ---- fixture -------------------------------------------------------------------------------

    private val serial = "R58M12345AB"
    private val bluetoothName = "Galaxy Buds Pro"
    private val signature = "3A:1F:90:2C:55:07:BE:41:6D:88:12:4E:9F:03:C7:5B"

    private fun spec(
        key: String,
        label: String,
        value: String,
        group: SpecGroup = SpecGroup.IDENTITY,
        status: DiagnosticStatus = DiagnosticStatus.VERIFIED,
        sensitive: Boolean = false,
        note: String? = null
    ) = SpecField(
        key = key,
        label = label,
        value = value,
        group = group,
        status = status,
        confidence = if (status == DiagnosticStatus.NOT_SUPPORTED) Confidence.NONE else Confidence.HIGH,
        verification = if (status == DiagnosticStatus.NOT_SUPPORTED) Verification.INACCESSIBLE
        else Verification.DIRECTLY_VERIFIED,
        source = "test",
        note = note,
        sensitive = sensitive
    )

    /** The three identifiers `docs/PRIVACY.md` says are redacted, plus the states the export separates. */
    private fun fields(): List<SpecField> = listOf(
        spec("identity.manufacturer", "Manufacturer", "samsung"),
        spec("identity.serial", "Serial number", serial, sensitive = true),
        spec("net.bluetoothName", "Bluetooth adapter name", bluetoothName,
            group = SpecGroup.CONNECTIVITY, sensitive = true),
        spec("security.signature", "APK signing certificate (SHA-256)", signature,
            group = SpecGroup.SECURITY, sensitive = true),
        spec("battery.cycleCount", "Charge cycles", "Not accessible", group = SpecGroup.BATTERY,
            status = DiagnosticStatus.NOT_SUPPORTED, note = "Only exposed from Android 14 (API 34)."),
        spec("net.wifiState", "Wi-Fi state", "Not available", group = SpecGroup.CONNECTIVITY,
            status = DiagnosticStatus.PERMISSION_REQUIRED, note = "Requires the location permission."),
        spec("camera.megapixels", "Rear camera resolution", "50.0 MP", group = SpecGroup.CAMERA,
            status = DiagnosticStatus.UNVERIFIED, note = "Reported by the camera stack only.")
    )

    private fun report(collectionErrors: List<String> = listOf("Camera collection failed: timed out after 20 s")): DeviceReport {
        val snapshot = TestFixtures.snapshot(
            identity = TestFixtures.identity().copy(serial = serial)
        ).copy(fields = fields(), collectionErrors = collectionErrors)
        val session = DiagnosticSession(
            listOf(
                DiagnosticResult(
                    testId = "touch.multi",
                    title = "Multi-touch",
                    status = DiagnosticStatus.PASS,
                    measured = "10 simultaneous points",
                    note = "Every corner registered.",
                    timestampMillis = TestFixtures.NOW
                ),
                DiagnosticResult(
                    testId = "battery.charging",
                    title = "Charging",
                    status = DiagnosticStatus.UNVERIFIED,
                    measured = null,
                    note = "Not plugged in.",
                    timestampMillis = TestFixtures.NOW
                )
            )
        )
        val parts = DefaultPartsProvider().assess(snapshot, TestFixtures.referenceMatch(), session)
        val integrity = DefaultIntegrityAnalyzer().analyze(snapshot)
        return DeviceReport(
            snapshot = snapshot,
            authenticity = DefaultAuthenticityEngine().analyze(snapshot, TestFixtures.referenceMatch()),
            parts = parts,
            age = DefaultAgeEstimator().estimate(snapshot, TestFixtures.referenceMatch(), null),
            integrity = integrity,
            scores = DefaultScoreEngine().score(snapshot, parts, integrity, session),
            session = session,
            generatedAtMillis = TestFixtures.NOW,
            appVersion = "1.0.0",
            redacted = true
        )
    }

    private fun encoded(report: DeviceReport, redacted: Boolean): String =
        ReportJson.encode(report, redacted)

    private fun tree(report: DeviceReport, redacted: Boolean): JsonObject =
        parser.parseToJsonElement(encoded(report, redacted)).jsonObject

    private fun JsonObject.keysAt(path: String): Set<String> = this[path]!!.jsonObject.keys

    private fun firstElement(array: JsonElement): JsonObject = (array as JsonArray).first().jsonObject

    /** Every leaf of the document, keyed by its path, so two encodings can be compared exactly. */
    private fun flatten(
        element: JsonElement,
        path: String,
        into: MutableMap<String, String>
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                flatten(value, if (path.isEmpty()) key else "$path.$key", into)
            }
            is JsonArray -> element.forEachIndexed { index, value -> flatten(value, "$path[$index]", into) }
            else -> into[path] = element.toString()
        }
    }

    private fun leaves(report: DeviceReport, redacted: Boolean): Map<String, String> {
        val into = LinkedHashMap<String, String>()
        flatten(tree(report, redacted), "", into)
        return into
    }

    // ---- schema --------------------------------------------------------------------------------

    @Test
    fun `top-level keys match the documented schema`() {
        val json = tree(report(), redacted = true)
        assertEquals(
            setOf(
                "reportFormatVersion", "app", "appVersion", "generatedAt", "generatedAtMillis",
                "redacted", "mockData", "disclaimer", "platform", "identity", "operatingSystem",
                "specifications", "authenticity", "parts", "age", "integrity", "scores",
                "diagnostics", "unknownOrInaccessible", "collectionErrors"
            ),
            json.keys
        )
        assertEquals(1, json["reportFormatVersion"]!!.jsonPrimitive.int)
        assertEquals("Verify Device Info", json["app"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every nested object keeps its documented keys`() {
        val json = tree(report(), redacted = true)

        assertEquals(setOf("os", "collectedAt"), json.keysAt("platform"))
        assertEquals(
            setOf(
                "manufacturer", "brand", "model", "marketingName", "device", "product", "hardware",
                "board", "fingerprint", "buildId", "buildTags", "buildType", "socManufacturer",
                "socModel", "serial"
            ),
            json.keysAt("identity")
        )
        assertEquals(setOf("release", "apiLevel", "securityPatch", "kernel", "uptime"),
            json.keysAt("operatingSystem"))
        assertEquals(
            setOf(
                "key", "label", "group", "value", "status", "confidence", "verification", "source", "note"
            ),
            firstElement(json["specifications"]!!).keys
        )
        assertEquals(setOf("headline", "riskLevel", "referenceSource", "findings"),
            json.keysAt("authenticity"))
        val finding = firstElement(json["authenticity"]!!.jsonObject["findings"]!!)
        assertEquals(
            setOf("id", "title", "status", "risk", "confidence", "explanation", "evidence"),
            finding.keys
        )
        assertEquals(
            setOf("description", "source", "detail"),
            firstElement(finding["evidence"]!!).keys
        )
        assertEquals(setOf("components", "limitations"), json.keysAt("parts"))
        val component = firstElement(json["parts"]!!.jsonObject["components"]!!)
        assertEquals(setOf("component", "state", "confidence", "explanation", "evidence"), component.keys)
        assertEquals(
            setOf("description", "source"),
            firstElement(component["evidence"]!!).keys
        )
        assertEquals(
            setOf(
                "modelLaunchDate", "modelLaunchAge", "firmwareBuildDate", "firmwareBuildAge",
                "ownershipAge", "estimatedUsageAge", "securityPatchDate", "daysSinceSecurityPatch",
                "confidence", "notes"
            ),
            json.keysAt("age")
        )
        assertEquals(setOf("overall", "summary", "checks"), json.keysAt("integrity"))
        assertEquals(
            setOf("id", "title", "status", "explanation"),
            firstElement(json["integrity"]!!.jsonObject["checks"]!!).keys
        )
        val score = json["scores"]!!.jsonObject.values.first().jsonObject
        assertEquals(setOf("score", "band", "coveragePercent", "summary", "inputs"), score.keys)
        assertEquals(
            setOf("label", "value", "weight", "note"),
            firstElement(score["inputs"]!!).keys
        )
        assertEquals(
            setOf("testId", "title", "status", "measured", "note", "timestamp"),
            firstElement(json["diagnostics"]!!).keys
        )
        assertEquals(
            setOf("key", "label", "status", "reason"),
            firstElement(json["unknownOrInaccessible"]!!).keys
        )
    }

    @Test
    fun `the seven documented scores are exported by name`() {
        val scores = tree(report(), redacted = true)["scores"]!!.jsonObject
        assertEquals(
            setOf(
                "Hardware health",
                "Software and security",
                "Component authenticity confidence",
                "Battery condition",
                "Display and sensors",
                "Performance",
                "Repair evidence completeness"
            ),
            scores.keys
        )
        // A withheld score is a JSON null, not an omitted key and not a zero.
        scores.values.forEach { score ->
            val element = score.jsonObject["score"]!!
            assertTrue("score must be an int or null", element is JsonNull || element is JsonPrimitive)
        }
    }

    @Test
    fun `unknownOrInaccessible lists exactly the fields that were not established`() {
        val report = report()
        val listed = tree(report, redacted = true)["unknownOrInaccessible"]!!.jsonArray
            .map { it.jsonObject["key"]!!.jsonPrimitive.content }
        val expected = report.snapshot.fields
            .filter {
                it.status == DiagnosticStatus.NOT_SUPPORTED ||
                    it.status == DiagnosticStatus.UNVERIFIED ||
                    it.status == DiagnosticStatus.PERMISSION_REQUIRED
            }
            .map { it.key }
        assertEquals(expected, listed)
        assertFalse(listed.contains("identity.manufacturer"))
    }

    @Test
    fun `collectionErrors appears only when there are errors`() {
        val withErrors = tree(report(), redacted = true)
        assertEquals(
            listOf("Camera collection failed: timed out after 20 s"),
            withErrors["collectionErrors"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        val withoutErrors = tree(report(collectionErrors = emptyList()), redacted = true)
        assertNull(withoutErrors["collectionErrors"])
    }

    @Test
    fun `null values are emitted as JSON null rather than dropped`() {
        val json = tree(report(), redacted = true)
        // The fixture device has no readable serial on an unredacted export of a serial-less identity,
        // and no marketing name is guaranteed, so the contract is "key present, value null".
        val identity = json["identity"]!!.jsonObject
        assertTrue(identity.containsKey("marketingName"))
        assertTrue(identity.containsKey("socModel"))
        val diagnostics = json["diagnostics"]!!.jsonArray
        assertEquals(JsonNull, diagnostics[1].jsonObject["measured"])
        assertEquals("Not plugged in.", diagnostics[1].jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encoding is deterministic and round-trips through the parser`() {
        val report = report()
        val first = encoded(report, redacted = true)
        val second = encoded(report, redacted = true)
        assertEquals(first, second)
        assertEquals(
            parser.parseToJsonElement(first),
            parser.parseToJsonElement(second)
        )
        assertTrue(first.startsWith("{"))
    }

    // ---- redaction -----------------------------------------------------------------------------

    @Test
    fun `redaction replaces exactly the serial and the flagged specification values`() {
        val report = report()
        val plain = leaves(report, redacted = false)
        val redacted = leaves(report, redacted = true)

        assertEquals("the same keys must be present in both exports", plain.keys, redacted.keys)

        val changed = plain.keys.filter { plain[it] != redacted[it] }.toSet()
        val expected = buildSet {
            add("redacted")
            add("identity.serial")
            report.snapshot.fields.forEachIndexed { index, field ->
                if (field.sensitive) add("specifications[$index].value")
            }
        }
        assertEquals("redaction touched more or less than the sensitive values", expected, changed)

        changed.forEach { path ->
            if (path == "redacted") {
                assertEquals("true", redacted[path])
            } else {
                assertEquals("$path was not replaced", "\"REDACTED\"", redacted[path])
            }
        }
    }

    @Test
    fun `the documented identifiers are the only sensitive fields in the fixture`() {
        val sensitiveKeys = fields().filter { it.sensitive }.map { it.key }.toSet()
        assertEquals(
            setOf("identity.serial", "net.bluetoothName", "security.signature"),
            sensitiveKeys
        )
    }

    @Test
    fun `no raw identifier survives in a redacted export`() {
        val text = encoded(report(), redacted = true)
        assertFalse(text.contains(serial))
        assertFalse(text.contains(bluetoothName))
        assertFalse(text.contains(signature))
    }

    @Test
    fun `an unredacted export keeps the identifiers`() {
        val text = encoded(report(), redacted = false)
        assertTrue(text.contains(serial))
        assertTrue(text.contains(bluetoothName))
        assertTrue(text.contains(signature))
        val json = tree(report(), redacted = false)
        assertEquals(serial, json["identity"]!!.jsonObject["serial"]!!.jsonPrimitive.content)
    }

    /**
     * A device that never disclosed a serial must not export `"REDACTED"`: that would claim an
     * identifier existed and was withheld. Android 10+ hides the serial from every unprivileged app,
     * so this is the normal case, not an edge case.
     */
    @Test
    fun `a null serial stays null when redacted`() {
        val snapshot = TestFixtures.snapshot(identity = TestFixtures.identity().copy(serial = null))
        val session = DiagnosticSession()
        val parts = DefaultPartsProvider().assess(snapshot, TestFixtures.referenceMatch(), session)
        val integrity = DefaultIntegrityAnalyzer().analyze(snapshot)
        val report = DeviceReport(
            snapshot = snapshot,
            authenticity = DefaultAuthenticityEngine().analyze(snapshot, TestFixtures.referenceMatch()),
            parts = parts,
            age = DefaultAgeEstimator().estimate(snapshot, TestFixtures.referenceMatch(), null),
            integrity = integrity,
            scores = DefaultScoreEngine().score(snapshot, parts, integrity, session),
            session = session,
            generatedAtMillis = TestFixtures.NOW,
            appVersion = "1.0.0",
            redacted = true
        )
        val identity = tree(report, redacted = true)["identity"]!!.jsonObject
        assertNotNull(identity["serial"])
        assertEquals(JsonNull, identity["serial"])
        assertEquals(JsonNull, tree(report, redacted = false)["identity"]!!.jsonObject["serial"])
        // The unredacted and redacted exports of a serial-less device are byte-identical apart from
        // the flag itself.
        val plain = leaves(report, redacted = false)
        val masked = leaves(report, redacted = true)
        assertEquals(setOf("redacted"), plain.keys.filter { plain[it] != masked[it] }.toSet())
    }

    @Test
    fun `the redacted flag in the document matches the export that was asked for`() {
        val report = report()
        assertEquals("true", leaves(report, redacted = true)["redacted"])
        assertEquals("false", leaves(report, redacted = false)["redacted"])
        // The flag follows the argument, not the report's own `redacted` field.
        assertEquals("false", leaves(report.copy(redacted = true), redacted = false)["redacted"])
    }
}
