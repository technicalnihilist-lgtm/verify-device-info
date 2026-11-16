package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.SystemPropertyReader
import com.verifydeviceinfo.data.android.collect.SecurityCollector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The security stage must survive every API level this app supports.
 *
 * `BiometricManager.canAuthenticate(int)` was added in **API 30**, but `BiometricManager` itself
 * exists from API 29 and the collector guarded the call at 29. On Android 10 the call therefore
 * reached a method the platform does not have: `NoSuchMethodError`, a `LinkageError`, which
 * `StageTimer` deliberately contains as a *failed stage* rather than a crash — so the whole
 * security section silently degraded to `fallbackSecurity()` on every Android 10 device, and the
 * integrity report went on to score a root check whose evidence had never been collected.
 *
 * Lint's NewApi check reported the same requirement (`api 30`); these two cases pin it, one on the
 * API level that used to fail and one on the first level that has the method.
 */
@RunWith(AndroidJUnit4::class)
class SecurityCollectorBiometricApiLevelTest {

    private fun collector(): SecurityCollector =
        SecurityCollector(
            ApplicationProvider.getApplicationContext<Context>(),
            // No device here, so the property dump is empty; the collector's reflection fallback
            // and its per-row defaults are what it would use on a device that publishes nothing.
            SystemPropertyReader { emptyMap() }
        )

    @Test
    @Config(sdk = [29])
    fun theSecurityStageStillProducesItsRowsOnAndroidTen() {
        // Before the fix this threw NoSuchMethodError out of `collect()`.
        val result = collector().collect()

        assertTrue(
            "the security stage must still report the properties it reads",
            result.fields.isNotEmpty()
        )
        assertNotNull(
            "the biometric rows are built after the canAuthenticate calls, so their presence is what " +
                "proves the API-level guard holds",
            result.biometricFields.firstOrNull { it.key == "biometric.strong" }
        )
        assertTrue(
            "Android 10 has BiometricManager but not canAuthenticate(int), so nothing may be claimed",
            !result.biometricStrongAvailable && !result.biometricAnyAvailable
        )
    }

    @Test
    // sdk 34 rather than the first level that has the method: Robolectric builds one sandbox per
    // SDK level a run touches, and every other test here already uses 29, 33 or 34.
    @Config(sdk = [34])
    fun theBiometricRowsAreReportedOnTheLevelsThatHaveTheMethod() {
        val result = collector().collect()

        val strong = result.biometricFields.firstOrNull { it.key == "biometric.strong" }
        assertNotNull("API 30+ is where canAuthenticate(int) exists, so it must be called here", strong)
        // The value itself is the framework's answer and is not asserted here: Robolectric's
        // BiometricManager shadow answers for the whole device model rather than for hardware, and
        // what this case exists to pin is that the call is reachable on this API level at all.
        assertTrue(
            "the row must carry one of the documented answers, not a crash or an empty cell",
            strong!!.value.isNotBlank()
        )
    }
}
