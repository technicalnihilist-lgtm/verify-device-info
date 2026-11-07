package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.collect.gpuVendorOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GPU vendor is inferred from the GL renderer string alone, so the matcher has to survive the
 * spellings real drivers emit: brand first or architecture first, upper or mixed case, with the
 * marketing suffix still attached.
 *
 * The regression this file exists for: the matcher lowercases the renderer and then compared it with
 * the mixed-case literal `"rDNA"`, which can never be a substring of a lowercased string. The RDNA
 * half of the AMD branch was therefore dead, and a renderer that named RDNA and nothing else — which
 * is what the Xclipse drivers on some builds report — fell through to `null` and rendered as
 * "Unknown". `amdRdnaRendererIsRecognised` fails on that code and passes on the fix.
 */
class GpuVendorTest {

    @Test
    fun amdRdnaRendererIsRecognised() {
        assertEquals("AMD (Samsung Xclipse)", gpuVendorOf("AMD RDNA 2"))
    }

    @Test
    fun rdnaIsMatchedInAnyCase() {
        listOf("RDNA", "rdna", "Rdna2", "RDNA 3", "amd rdna").forEach { renderer ->
            assertEquals(renderer, "AMD (Samsung Xclipse)", gpuVendorOf(renderer))
        }
    }

    @Test
    fun xclipseIsMatchedInAnyCase() {
        listOf("Samsung Xclipse 920", "XCLIPSE 530", "xclipse-940").forEach { renderer ->
            assertEquals(renderer, "AMD (Samsung Xclipse)", gpuVendorOf(renderer))
        }
    }

    @Test
    fun rendererTableMapsToTheExpectedVendor() {
        val table = mapOf(
            "Adreno (TM) 740" to "Qualcomm",
            "ADRENO 642L" to "Qualcomm",
            "Qualcomm Adreno 610" to "Qualcomm",
            "kgsl-3d0" to "Qualcomm",
            "Mali-G78 MP14" to "Arm",
            "mali-g52" to "Arm",
            "MALI_G710" to "Arm",
            "PowerVR Rogue GE8320" to "Imagination Technologies",
            "powervr ge8322" to "Imagination Technologies",
            "Rogue GE8300" to "Imagination Technologies",
            "Samsung Xclipse 920" to "AMD (Samsung Xclipse)",
            "AMD RDNA 2" to "AMD (Samsung Xclipse)"
        )
        table.forEach { (renderer, expected) ->
            assertEquals(renderer, expected, gpuVendorOf(renderer))
        }
    }

    @Test
    fun unknownRendererReportsNothingRatherThanAGuess() {
        listOf("Vulkan 1.3", "Google SwiftShader", "llvmpipe", "Apple M1", "0x0000").forEach { renderer ->
            assertNull(renderer, gpuVendorOf(renderer))
        }
    }

    @Test
    fun missingRendererReportsNothing() {
        assertNull(gpuVendorOf(null))
        assertNull(gpuVendorOf(""))
        assertNull(gpuVendorOf("   "))
    }
}
