package com.verifydeviceinfo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.collect.HardwareCollector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The display section must be populated, not silently dropped, on a framework that answers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class HardwareCollectorDisplayTest {

    @Test
    fun displaySectionIsCollected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = HardwareCollector(context).collect()

        assertNotNull("collect() must not throw away the display facts", result.display)
        assertTrue(result.display!!.widthPx > 0)
        assertTrue(result.display!!.heightPx > 0)
        assertTrue(result.display!!.densityDpi > 0)
        assertTrue(result.display!!.refreshRateHz > 0f)
        assertTrue(
            "display resolution must reach the report",
            result.fields.any { it.key == "display.resolution" }
        )
    }
}
