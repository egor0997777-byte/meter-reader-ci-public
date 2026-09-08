package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class TakeReadingsShortcutRoutingTest {
    private val september = YearMonth.of(2026, 9)

    @Test
    fun resumesExistingWalkWithoutResettingItsPeriod() {
        val home = Address(id = "home", name = "Home", meters = listOf(Meter(id = "water", name = "Water", unit = "m3")))

        val target = TakeReadingsShortcutRouting.resolve(
            addresses = listOf(home),
            sessionAddressId = "home",
            sessionPeriod = YearMonth.of(2026, 8),
            now = september
        )

        assertEquals("home", target?.addressId)
        assertEquals(YearMonth.of(2026, 8), target?.period)
        assertTrue(target?.resumeExisting == true)
    }

    @Test
    fun opensFirstAddressThatStillNeedsAReadingThisPeriod() {
        val complete = Address(
            id = "complete",
            name = "Complete",
            meters = listOf(
                Meter(
                    id = "water-a",
                    name = "Water",
                    unit = "m3",
                    readings = listOf(Reading(value = 10.0, valueText = "10", billingPeriod = "2026-09"))
                )
            )
        )
        val pending = Address(
            id = "pending",
            name = "Pending",
            meters = listOf(Meter(id = "water-b", name = "Water", unit = "m3"))
        )

        val target = TakeReadingsShortcutRouting.resolve(listOf(complete, pending), null, null, september)

        assertEquals("pending", target?.addressId)
        assertEquals(september, target?.period)
        assertFalse(target?.resumeExisting ?: true)
    }

    @Test
    fun closedMetersDoNotCreateFalseRemainingWork() {
        val address = Address(
            id = "home",
            name = "Home",
            meters = listOf(
                Meter(id = "old", name = "Old", unit = "m3", status = "closed"),
                Meter(
                    id = "active",
                    name = "Active",
                    unit = "m3",
                    readings = listOf(Reading(value = 42.0, valueText = "42", billingPeriod = "2026-09"))
                )
            )
        )

        assertEquals(0, TakeReadingsShortcutRouting.remainingForPeriod(address, september))
    }

    @Test
    fun storedBillingPeriodWinsOverTimestampFallback() {
        val address = Address(
            id = "home",
            name = "Home",
            meters = listOf(
                Meter(
                    id = "water",
                    name = "Water",
                    unit = "m3",
                    readings = listOf(Reading(value = 5.0, timestamp = 0L, valueText = "5", billingPeriod = "2026-09"))
                )
            )
        )

        assertEquals(0, TakeReadingsShortcutRouting.remainingForPeriod(address, september))
    }

    @Test
    fun returnsNullWhenThereAreNoActiveMeters() {
        val address = Address(
            id = "home",
            name = "Home",
            meters = listOf(Meter(id = "old", name = "Old", unit = "m3", status = "closed"))
        )

        assertNull(TakeReadingsShortcutRouting.resolve(listOf(address), null, null, september))
    }
}
