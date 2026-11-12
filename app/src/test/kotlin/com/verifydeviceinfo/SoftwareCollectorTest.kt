package com.verifydeviceinfo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.data.android.collect.SoftwareCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the software section reports when the package list cannot be read.
 *
 * `SoftwareCollector` wrapped `PackageManager.getInstalledPackages(0)` in a `try` whose `catch` only
 * flipped a flag nobody read for the rows, so both counters stayed at zero and the section printed
 * `"Installed apps visible to this app: 0"`, `"System apps: 0"` and `"User-installed apps: 0"` — three
 * VERIFIED measurements with HIGH confidence, on a device whose package list was never read. This is
 * the camera, sensor and audio defect (Z1, Z2, Z3) in the software section: a refused read became a
 * measurement of absence.
 *
 * The read is now a [SoftwareCollector] constructor parameter so the failure can be driven here:
 * production passes nothing and keeps using `PackageManager.getInstalledPackages(0)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SoftwareCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun packageInfo(system: Boolean): PackageInfo = PackageInfo().apply {
        applicationInfo = ApplicationInfo().apply {
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
    }

    private fun row(fields: List<SpecField>, key: String): SpecField =
        fields.first { it.key == key }

    private val countKeys = listOf(
        "software.packageCount", "software.systemPackages", "software.userPackages"
    )

    @Test
    fun `a refused package list is not reported as a device with no apps`() {
        val collector = SoftwareCollector(context) { throw IllegalStateException("package manager refused") }

        val (facts, fields) = collector.collect()

        countKeys.forEach { key ->
            val field = row(fields, key)
            assertEquals("Unavailable", field.value)
            assertFalse("$key must not report a count it never read", field.value == "0")
            assertEquals(DiagnosticStatus.UNVERIFIED, field.status)
            assertEquals(Confidence.NONE, field.confidence)
            assertEquals("Unreadable: package list failed: package manager refused", field.note)
        }
        // The lower-bound note belongs to a count that was taken; it must not stand in for a failure.
        assertFalse(row(fields, "software.packageCount").note!!.contains("lower bound"))
        assertFalse(facts.packageVisibilityRestricted)
    }

    @Test
    fun `an exception with no message still names what failed`() {
        val collector = SoftwareCollector(context) { throw IllegalStateException() }

        val (_, fields) = collector.collect()

        assertEquals(
            "Unreadable: package list failed: IllegalStateException",
            row(fields, "software.packageCount").note
        )
    }

    @Test
    fun `a package list that answers still reports the counts it read`() {
        val collector = SoftwareCollector(context) {
            listOf(packageInfo(system = true), packageInfo(system = true), packageInfo(system = false))
        }

        val (facts, fields) = collector.collect()

        assertEquals("3", row(fields, "software.packageCount").value)
        assertEquals("2", row(fields, "software.systemPackages").value)
        assertEquals("1", row(fields, "software.userPackages").value)
        assertEquals(DiagnosticStatus.VERIFIED, row(fields, "software.packageCount").status)
        assertEquals(Confidence.HIGH, row(fields, "software.packageCount").confidence)
        assertNull(row(fields, "software.systemPackages").note)
        // Android 11+ still gets the note that explains why the count is a lower bound.
        assertTrue(row(fields, "software.packageCount").note!!.contains("lower bound"))
        assertEquals(3, facts.installedPackageCount)
        assertEquals(2, facts.systemPackageCount)
        assertEquals(1, facts.userPackageCount)
        assertTrue(facts.packageVisibilityRestricted)
    }

    @Test
    fun `an empty but answered list still reports zero apps`() {
        val collector = SoftwareCollector(context) { emptyList() }

        val (facts, fields) = collector.collect()

        countKeys.forEach { key -> assertEquals("0", row(fields, key).value) }
        assertEquals(DiagnosticStatus.VERIFIED, row(fields, "software.packageCount").status)
        assertEquals(0, facts.installedPackageCount)
        assertTrue(facts.packageVisibilityRestricted)
    }
}
