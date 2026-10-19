package io.github.usmandurrany.tether.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveKeepaliveTest {
    private val policy = KeepalivePolicy(initialMillis = 60_000, minMillis = 30_000, maxMillis = 150_000, stepMillis = 30_000, successesToGrow = 2)

    @Test
    fun growsAfterARunOfSuccessesUpToTheMaximum() {
        val ka = AdaptiveKeepalive(policy)
        repeat(20) { ka.onIdleSuccess("wifi") }
        assertEquals(150_000, ka.intervalFor("wifi"))
    }

    @Test
    fun aDeathStepsDownAndCapsTheNetworkBelowTheFailingInterval() {
        val ka = AdaptiveKeepalive(policy)
        repeat(4) { ka.onIdleSuccess("cell") } // 60 → 90 → 120
        assertEquals(120_000, ka.intervalFor("cell"))
        ka.onIdleDeath("cell")
        assertEquals(90_000, ka.intervalFor("cell"))
        repeat(20) { ka.onIdleSuccess("cell") }
        assertEquals(90_000, ka.intervalFor("cell"), "never climbs back into the NAT timeout")
    }

    @Test
    fun neverGoesBelowTheMinimum() {
        val ka = AdaptiveKeepalive(policy)
        repeat(10) { ka.onIdleDeath(null) }
        assertEquals(30_000, ka.intervalFor(null))
    }

    @Test
    fun networksAreIndependentAndSnapshotsRoundTrip() {
        val ka = AdaptiveKeepalive(policy)
        repeat(2) { ka.onIdleSuccess("a") }
        ka.onIdleDeath("b")
        assertEquals(60_000, ka.intervalFor("c"))
        val restored = AdaptiveKeepalive(policy).apply { restore(ka.snapshot()) }
        assertEquals(90_000, restored.intervalFor("a"))
        assertEquals(30_000, restored.intervalFor("b"))
    }

    @Test
    fun restoreClampsValuesOutsideThePolicy() {
        val ka = AdaptiveKeepalive(policy)
        ka.restore(mapOf("x" to (10_000_000L to 10_000_000L), "y" to (1L to 1L)))
        assertEquals(150_000, ka.intervalFor("x"))
        assertEquals(30_000, ka.intervalFor("y"))
    }
}
