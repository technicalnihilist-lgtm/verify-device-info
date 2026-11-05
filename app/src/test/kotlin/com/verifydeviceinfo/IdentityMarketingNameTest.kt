package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.SystemPropertyReader
import com.verifydeviceinfo.data.android.collect.IdentityCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marketing name lives in several property namespaces and a build populates only some of them.
 * These tests pin the order they are tried in, and that the field names the key that answered.
 */
class IdentityMarketingNameTest {

    private fun reader(vararg props: Pair<String, String>) =
        SystemPropertyReader { props.toMap() }

    @Test
    fun systemKeyWinsWhenSeveralArePresent() {
        val answer = IdentityCollector.marketingName(
            reader(
                "ro.product.marketname" to "Pixel 7",
                "ro.product.vendor.marketname" to "Vendor Name",
                "ro.vendor.product.marketname" to "Legacy Name"
            )
        )

        assertEquals("ro.product.marketname" to "Pixel 7", answer)
    }

    @Test
    fun vendorPartitionKeyIsUsedWhenTheSystemKeyIsAbsent() {
        // The standard partition-qualified spelling, which the old three-key lookup never read.
        val answer = IdentityCollector.marketingName(
            reader(
                "ro.product.vendor.marketname" to "Galaxy S23",
                "ro.product.odm.marketname" to "Odm Name"
            )
        )

        assertEquals("ro.product.vendor.marketname" to "Galaxy S23", answer)
    }

    @Test
    fun odmPartitionKeyIsUsedWhenNothingEarlierAnswers() {
        val answer = IdentityCollector.marketingName(
            reader("ro.product.odm.marketname" to "OnePlus 11")
        )

        assertEquals("ro.product.odm.marketname" to "OnePlus 11", answer)
    }

    @Test
    fun legacyOemSpellingIsTriedLast() {
        val answer = IdentityCollector.marketingName(
            reader(
                "ro.product.vendor.marketname" to "Standard Name",
                "ro.vendor.product.marketname" to "Legacy Name"
            )
        )

        assertEquals("ro.product.vendor.marketname" to "Standard Name", answer)

        val onlyLegacy = IdentityCollector.marketingName(
            reader("ro.vendor.product.marketname" to "Legacy Name")
        )
        assertEquals("ro.vendor.product.marketname" to "Legacy Name", onlyLegacy)
    }

    @Test
    fun blankValuesAreSkippedRatherThanReported() {
        val answer = IdentityCollector.marketingName(
            reader(
                "ro.product.marketname" to "   ",
                "ro.product.vendor.marketname" to ""
            )
        )

        assertNull(answer)
    }

    @Test
    fun noKeyAtAllReturnsNull() {
        assertNull(IdentityCollector.marketingName(reader("ro.product.model" to "redfin")))
    }

    @Test
    fun keyTableHasNoDuplicatesAndStartsWithThePlatformKey() {
        val keys = IdentityCollector.MARKETING_NAME_KEYS

        assertEquals(keys.size, keys.distinct().size)
        assertEquals("ro.product.marketname", keys.first())
        assertTrue(keys.all { it.startsWith("ro.") && it.endsWith("marketname") })
    }
}
