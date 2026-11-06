package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.collect.withThrowAwayKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A throw-away keystore key must never outlive the probe that created it.
 *
 * The security collector generates one key under a fixed alias and deletes it again. A keystore
 * refuses to overwrite an existing alias, so a key left behind by an interrupted probe used to make
 * every later probe fail — silently reporting "no hardware keystore" for the life of the install —
 * and stayed in the user's keystore. These cases pin the two halves of the fix: the alias is free
 * when the probe runs, and it is free again when the probe ends, whether it returned or threw.
 *
 * A plain JVM `PKCS12` keystore stands in for the Android keystore: the contract under test
 * ("the alias is not occupied when the block starts, and is not occupied when it ends") is a
 * `KeyStore` contract, not an Android one, so no device and no Robolectric are involved.
 */
class SecurityProbeKeyTest {

    private val alias = "verify_device_info_probe"

    private fun store(): KeyStore =
        KeyStore.getInstance("PKCS12").apply { load(null, PASSWORD) }

    private fun KeyStore.putKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(PASSWORD))
    }

    @Test
    fun `a key left behind by an earlier probe is removed before the probe runs`() {
        val store = store()
        store.putKey()

        var occupiedWhenTheProbeStarted = true
        withThrowAwayKey(store, alias) { occupiedWhenTheProbeStarted = store.containsAlias(alias) }

        assertFalse(
            "the probe must start with the alias free, or key generation fails with 'alias exists'",
            occupiedWhenTheProbeStarted
        )
    }

    @Test
    fun `the probe key is gone once the probe returns`() {
        val store = store()

        val result = withThrowAwayKey(store, alias) {
            store.putKey()
            assertTrue("the key the probe generated should be there while it runs", store.containsAlias(alias))
            "reading"
        }

        assertEquals("reading", result)
        assertFalse("the probe key must not be left in the keystore", store.containsAlias(alias))
    }

    @Test
    fun `the probe key is gone even when the probe throws`() {
        val store = store()

        val thrown = runCatching {
            withThrowAwayKey(store, alias) {
                store.putKey()
                throw IllegalStateException("characteristics unreadable")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse("a failed probe must not leave its key behind", store.containsAlias(alias))
    }

    @Test
    fun `a stale key does not survive a probe that fails before generating its own`() {
        val store = store()
        store.putKey()

        runCatching { withThrowAwayKey(store, alias) { throw IllegalStateException("no keystore") } }

        assertFalse("the stale key must be cleared even if the probe never got as far as a key", store.containsAlias(alias))
    }

    @Test
    fun `the probe value is returned unchanged`() {
        assertEquals(7, withThrowAwayKey(store(), alias) { 7 })
    }

    @Test
    fun `an absent alias is not an error`() {
        val store = store()
        assertFalse(store.containsAlias(alias))
        assertEquals("ok", withThrowAwayKey(store, alias) { "ok" })
    }

    private companion object {
        val PASSWORD = "probe-test".toCharArray()
    }
}
