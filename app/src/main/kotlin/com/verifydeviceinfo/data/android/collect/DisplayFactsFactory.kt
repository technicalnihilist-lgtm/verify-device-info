package com.verifydeviceinfo.data.android.collect

import com.verifydeviceinfo.core.model.DisplayFacts
import kotlin.math.sqrt

/**
 * Builds [DisplayFacts] from raw metric values.
 *
 * Deliberately free of framework types so the arithmetic — and the guards around the physical
 * dpi and refresh rate a vendor reports — can be exercised by plain unit tests without a device.
 */
internal object DisplayFactsFactory {

    /** Used when a display reports no usable refresh rate; matches the value the collector always fell back to. */
    const val FALLBACK_REFRESH_HZ = 60f

    /**
     * Diagonal in inches from the reported pixel counts and physical dpi, or 0 when either dpi is
     * missing or nonsensical. 0 is what the report renders as "0.00 in", which is the same thing it
     * rendered before this calculation was extracted.
     */
    fun diagonalInches(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float): Float {
        if (widthPx <= 0 || heightPx <= 0 || xdpi <= 0f || ydpi <= 0f) return 0f
        val widthInches = widthPx / xdpi
        val heightInches = heightPx / ydpi
        val diagonal = sqrt(widthInches * widthInches + heightInches * heightInches)
        return if (diagonal.isFinite()) diagonal else 0f
    }

    fun build(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        xdpi: Float,
        ydpi: Float,
        refreshRateHz: Float,
        supportedRefreshRates: List<Float>,
        hdrTypes: List<String>,
        wideColorGamut: Boolean
    ): DisplayFacts {
        val refresh = if (refreshRateHz > 0f && refreshRateHz.isFinite()) {
            refreshRateHz
        } else {
            FALLBACK_REFRESH_HZ
        }
        return DisplayFacts(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            refreshRateHz = refresh,
            supportedRefreshRates = supportedRefreshRates.distinct().sorted(),
            hdrTypes = hdrTypes.distinct(),
            xdpi = xdpi,
            ydpi = ydpi,
            diagonalInches = diagonalInches(widthPx, heightPx, xdpi, ydpi),
            wideColorGamut = wideColorGamut
        )
    }
}
