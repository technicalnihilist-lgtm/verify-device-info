package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.WeakRefHolder
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the contract the permission manager relies on: a holder never keeps its referent alive, and a
 * detach from an object that has already been replaced cannot take the holder away from the current one.
 */
class WeakRefHolderTest {

    private class Owner(val name: String)

    /** Sets a referent and returns with no strong reference to it left on the caller's stack. */
    private fun setAndDrop(holder: WeakRefHolder<Owner>) {
        holder.set(Owner("dropped"))
    }

    @Test
    fun emptyHolderReturnsNull() {
        assertNull(WeakRefHolder<Owner>().get())
    }

    @Test
    fun setThenGetReturnsTheSameInstance() {
        val holder = WeakRefHolder<Owner>()
        val owner = Owner("a")
        holder.set(owner)
        assertSame(owner, holder.get())
    }

    @Test
    fun clearWithTheHeldInstanceEmptiesTheHolder() {
        val holder = WeakRefHolder<Owner>()
        val owner = Owner("a")
        holder.set(owner)
        holder.clear(owner)
        assertNull(holder.get())
    }

    @Test
    fun clearWithADifferentInstanceLeavesTheHolderAlone() {
        // A late detach from a destroyed activity must not unbind the activity that replaced it.
        val holder = WeakRefHolder<Owner>()
        val old = Owner("old")
        val current = Owner("current")
        holder.set(old)
        holder.set(current)
        holder.clear(old)
        assertSame(current, holder.get())
    }

    @Test
    fun setReplacesThePreviousReferent() {
        val holder = WeakRefHolder<Owner>()
        val first = Owner("first")
        val second = Owner("second")
        holder.set(first)
        holder.set(second)
        assertSame(second, holder.get())
    }

    @Test
    fun aRepeatedClearIsHarmless() {
        val holder = WeakRefHolder<Owner>()
        val owner = Owner("a")
        holder.set(owner)
        holder.clear(owner)
        holder.clear(owner)
        assertNull(holder.get())
    }

    @Test
    fun theHolderDoesNotKeepTheReferentAlive() {
        val holder = WeakRefHolder<Owner>()
        setAndDrop(holder)
        // The only reference left is the holder's weak one; it must not survive collection.
        repeat(200) {
            if (holder.get() == null) return
            System.gc()
            Thread.sleep(10)
        }
        assertNull(holder.get())
    }
}
