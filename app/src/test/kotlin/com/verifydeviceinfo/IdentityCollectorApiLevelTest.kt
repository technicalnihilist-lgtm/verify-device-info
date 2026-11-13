package com.verifydeviceinfo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.data.android.SystemPropertyReader
import com.verifydeviceinfo.data.android.collect.IdentityCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The identity section on a platform that predates the SoC fields.
 *
 * `Build.SOC_MANUFACTURER` and `Build.SOC_MODEL` are static fields added in API 31. A static field
 * is resolved when its instruction executes, not when the class is loaded, so
 * `Build.SOC_MANUFACTURER.takeIf { Build.VERSION.SDK_INT >= 31 }` read the field *before* the
 * predicate that was meant to guard it could run: on Android 8-11 the read threw
 * `NoSuchFieldError`. That is an `Error`, so `StageTimer.run` (which catches `Exception`) and
 * `AppViewModel.scan` (which also catches `Exception`) both let it through — the identity stage
 * died and the scan coroutine with it.
 *
 * The level is checked first now, and the two SoC rows report "Unavailable" with the same
 * "Requires Android 12 (API 31) or newer." note they always carried below API 31. These tests pin
 * both halves: the section survives on API 29, and the API 34 path still produces the same rows.
 */
@RunWith(AndroidJUnit4::class)
class IdentityCollectorApiLevelTest {

    /**
     * A reader with no property dump: every lookup falls through to reflection, which finds
     * nothing, so the collector reports what `android.os.Build` alone can answer.
     */
    private fun collector(): IdentityCollector =
        IdentityCollector(SystemPropertyReader { emptyMap() })

    private fun row(fields: List<SpecField>, key: String): SpecField =
        fields.first { it.key == key }

    /** Every row the identity section is expected to produce, whether or not its value is known. */
    private val identityKeys = listOf(
        "identity.manufacturer", "identity.brand", "identity.model", "identity.marketingName",
        "identity.device", "identity.product", "identity.hardware", "identity.board",
        "identity.bootloader", "identity.fingerprint", "identity.socManufacturer",
        "identity.socModel", "identity.serial"
    )

    @Test
    @Config(sdk = [29])
    fun `the identity section survives a platform without the SoC fields`() {
        val result = collector().collect()

        identityKeys.forEach { key ->
            assertTrue("the identity section must still produce $key", result.fields.any { it.key == key })
        }
        assertTrue(
            "collection must run to the end of the section, not stop at the SoC rows",
            result.softwareFields.any { it.key == "software.buildTime" }
        )
    }

    @Test
    @Config(sdk = [29])
    fun `below API 31 the SoC rows are unavailable and say why`() {
        val result = collector().collect()

        listOf("identity.socManufacturer", "identity.socModel").forEach { key ->
            val field = row(result.fields, key)
            assertEquals("Unavailable", field.value)
            assertEquals(DiagnosticStatus.UNVERIFIED, field.status)
            assertEquals("Requires Android 12 (API 31) or newer.", field.note)
        }
        assertNull(result.identity.socManufacturer)
        assertNull(result.identity.socModel)
    }

    @Test
    @Config(sdk = [34])
    fun `from API 31 the SoC rows are read rather than gated`() {
        val result = collector().collect()

        listOf("identity.socManufacturer", "identity.socModel").forEach { key ->
            val field = row(result.fields, key)
            // The value itself is whatever this platform publishes; what must not happen is the
            // "requires Android 12" gate being applied to a platform that has the field.
            assertNull("a supported platform must not be gated: $key", field.note)
        }
    }
}
