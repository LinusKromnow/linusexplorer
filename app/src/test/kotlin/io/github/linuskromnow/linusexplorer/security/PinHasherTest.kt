package io.github.linuskromnow.linusexplorer.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun verifiesOnlyTheRightPin() {
        val stored = PinHasher.hash("4711", iterations = 1000)
        assertTrue(PinHasher.verify("4711", stored))
        assertFalse(PinHasher.verify("4712", stored))
        assertFalse(PinHasher.verify("4711", "garbage"))
    }

    @Test
    fun saltsDiffer() {
        assertNotEquals(PinHasher.hash("1234", 1000), PinHasher.hash("1234", 1000))
    }

    @Test
    fun lockoutGrows() {
        fun delay(failures: Int) = LockoutPolicy.delayAfter(failures, freeAttempts = 2, baseDelayMs = 1000)
        assertEquals(0L, delay(1))
        assertEquals(1000L, delay(2))
        assertEquals(2000L, delay(3))
        assertEquals(128_000L, delay(100)) // growth stops after 7 doublings
        assertEquals(3_600_000L, LockoutPolicy.delayAfter(1000)) // and is capped at one hour
    }
}
