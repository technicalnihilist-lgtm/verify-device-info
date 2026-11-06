package com.verifydeviceinfo

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.collect.HardwareCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The display rectangle must be read from the non-deprecated `WindowMetrics` source on API 30+ and
 * from `Display.getRealMetrics` below it, with the same numbers either way.
 */
@RunWith(AndroidJUnit4::class)
class HardwareCollectorWindowMetricsTest {

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Test
    @Config(sdk = [33])
    fun api30PlusReportsTheMaximumWindowMetricsRectangle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = HardwareCollector(context).collect().display

        assertNotNull("the display section must survive the WindowMetrics path", display)
        val facts = display!!
        val bounds = windowManager(context).maximumWindowMetrics.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            assertEquals(bounds.width(), facts.widthPx)
            assertEquals(bounds.height(), facts.heightPx)
            assertEquals(context.resources.displayMetrics.densityDpi, facts.densityDpi)
        } else {
            // A framework that models no window metrics must fall through to the legacy path rather
            // than reporting nothing.
            assertTrue(facts.widthPx > 0)
            assertTrue(facts.heightPx > 0)
            assertTrue(facts.densityDpi > 0)
        }
        assertTrue("the resolution row must still be produced", facts.widthPx > 0)
    }

    @Test
    @Config(sdk = [29])
    fun api29KeepsTheDeprecatedRectangle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager(context).defaultDisplay.getRealMetrics(expected)

        val display = HardwareCollector(context).collect().display

        assertNotNull("the display section must survive the legacy path", display)
        val facts = display!!
        assertEquals(expected.widthPixels, facts.widthPx)
        assertEquals(expected.heightPixels, facts.heightPx)
        assertEquals(expected.densityDpi, facts.densityDpi)
    }
}
