package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.data.android.collect.ConnectivityCollector
import com.verifydeviceinfo.data.android.collect.Reading
import com.verifydeviceinfo.data.android.collect.attempt
import com.verifydeviceinfo.data.android.collect.locationProviderRow
import com.verifydeviceinfo.data.android.collect.unreadableNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The connectivity section of a scan, and the one-row-per-read rule it now follows.
 *
 * The section is a list of independent rows, but three of its blocks used to read several values
 * inside a single `try`: Bluetooth state and adapter name, the active network's transport and
 * internet capability, and the telephony block's SIM state, operator name and phone type. A throw
 * from the second read in any of them did two things at once — it discarded the row that had already
 * been read successfully (the enclosing `catch` replaced the block with one "Unreadable" row) and it
 * re-emitted a key that was already in the list, so the snapshot carried the same [SpecField] key
 * twice and the JSON export listed two different values for one property.
 *
 * Each read now goes through `attempt`, which is what the tests here pin: the helper's own contract,
 * the absence of duplicate keys on a working device, and the exact rows the section is expected to
 * produce.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConnectivityCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a successful read is returned and carries no failure`() {
        val reading = attempt { "Telstra" }
        assertEquals("Telstra", reading.value)
        assertNull(reading.failure)
    }

    @Test
    fun `a throwing read becomes a failure instead of escaping`() {
        val boom = IllegalStateException("telephony service unavailable")
        val reading = attempt<String> { throw boom }
        assertNull(reading.value)
        assertEquals(boom, reading.failure)
    }

    @Test
    fun `a read that returns null is not a failure`() {
        // "the platform has no value" and "the platform refused" must stay distinguishable: only the
        // second one earns an Unreadable note.
        val reading = attempt<String?> { null }
        assertNull(reading.value)
        assertNull(reading.failure)
    }

    @Test
    fun `the unreadable note keeps the wording the blocks already used`() {
        assertEquals(
            "Unreadable: no phone state permission",
            unreadableNote(SecurityException("no phone state permission"))
        )
    }

    @Test
    fun `no field key is emitted twice`() {
        val fields = ConnectivityCollector(context).collect().fields
        val duplicates = fields.groupingBy { it.key }.eachCount().filterValues { it > 1 }
        assertTrue("duplicate keys: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every connectivity row is present exactly once`() {
        val keys = ConnectivityCollector(context).collect().fields.map { it.key }
        val expected = listOf(
            "net.wifi", "net.bluetooth", "net.nfc", "net.gnss", "net.telephony", "net.usbHost",
            "net.bluetoothState", "net.bluetoothName", "net.nfcState",
            "net.activeTransport", "net.internetCapable",
            "net.simState", "net.operator", "net.phoneType", "net.radioType",
            "net.locationProviders"
        )
        // The Wi-Fi state row is absent when the platform exposes no Wi-Fi manager at all, which is
        // a pre-existing condition of the block rather than something this test can assert.
        val missing = expected.filter { it != "net.wifiState" && it !in keys }
        assertTrue("missing rows: $missing", missing.isEmpty())
        assertEquals("rows reported: $keys", keys.size, keys.distinct().size)
    }

    @Test
    fun `an unreadable row is unverified rather than a false measurement`() {
        val fields = ConnectivityCollector(context).collect().fields
        // Whatever Robolectric's device reports, a row with no value must not claim a reading: the
        // value is "Unavailable" and the status is UNVERIFIED, which is the contract FieldBuilder
        // gives every block that ends up with nothing.
        fields.filter { it.value == "Unavailable" }.forEach { field ->
            assertEquals(field.key, DiagnosticStatus.UNVERIFIED, field.status)
            assertNotNull("${field.key} carries no note", field.note ?: field.source)
        }
        assertFalse(fields.isEmpty())
    }

    // ---- Enabled location providers -------------------------------------------------------
    //
    // `isProviderEnabled` throws IllegalArgumentException for a provider the platform does not
    // publish, which is the normal case on a device with no GNSS. The row must keep the other
    // provider's reading instead of reporting the whole row as unreadable.

    @Test
    fun `both providers readable and enabled lists both`() {
        val row = locationProviderRow(Reading(true, null), Reading(true, null))
        assertEquals("GPS, Network", row.value)
        assertNull(row.note)
    }

    @Test
    fun `both providers readable and disabled says none enabled`() {
        val row = locationProviderRow(Reading(false, null), Reading(false, null))
        assertEquals("None enabled", row.value)
        assertNull(row.note)
    }

    @Test
    fun `an absent GPS provider keeps the network provider's reading`() {
        val absent = IllegalArgumentException("Provider \"gps\" doesn't exist")
        val row = locationProviderRow(Reading(null, absent), Reading(true, null))
        assertEquals("Network", row.value)
        assertNull("a row with a value needs no unreadable note", row.note)
    }

    @Test
    fun `an absent network provider keeps the GPS reading`() {
        val absent = IllegalArgumentException("Provider \"network\" doesn't exist")
        val row = locationProviderRow(Reading(true, null), Reading(null, absent))
        assertEquals("GPS", row.value)
        assertNull(row.note)
    }

    @Test
    fun `an unread provider is not reported as none enabled`() {
        // One provider is unknown, so "None enabled" would be a measurement the row never took.
        val absent = IllegalArgumentException("Provider \"gps\" doesn't exist")
        val row = locationProviderRow(Reading(null, absent), Reading(false, null))
        assertNull(row.value)
        assertEquals("Unreadable: Provider \"gps\" doesn't exist", row.note)
    }

    @Test
    fun `both providers absent reports the failure once`() {
        val gpsAbsent = IllegalArgumentException("Provider \"gps\" doesn't exist")
        val networkAbsent = IllegalArgumentException("Provider \"network\" doesn't exist")
        val row = locationProviderRow(Reading(null, gpsAbsent), Reading(null, networkAbsent))
        assertNull(row.value)
        assertEquals("Unreadable: Provider \"gps\" doesn't exist", row.note)
    }
}
