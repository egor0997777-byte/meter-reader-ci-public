package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MonthlyWalkPlannerTest {
    private val period = YearMonth.of(2026, 9)

    @Test
    fun continuesOnlyMetersMissingInSelectedPeriod() {
        val address = Address(
            id = "home",
            name = "Home",
            meters = listOf(
                Meter(
                    id = "cold",
                    name = "Cold",
                    unit = "m3",
                    readings = listOf(Reading(value = 12.0, valueText = "12", billingPeriod = "2026-09"))
                ),
                Meter(id = "hot", name = "Hot", unit = "m3"),
                Meter(id = "old", name = "Old", unit = "m3", status = "closed")
            )
        )

        val progress = MonthlyWalkPlanner.progress(address, period)

        assertEquals(2, progress.activeCount)
        assertEquals(1, progress.completedCount)
        assertEquals(1, progress.remainingCount)
        assertEquals(setOf("cold"), MonthlyWalkPlanner.completedMeterIds(address, period))
    }

    @Test
    fun allExistingReadingsOpenReviewInsteadOfDuplicateEntry() {
        val address = Address(
            id = "home",
            name = "Home",
            meters = listOf(
                Meter(id = "cold", name = "Cold", unit = "m3", readings = listOf(Reading(value = 1.0, valueText = "1", billingPeriod = "2026-09"))),
                Meter(id = "power", name = "Power", unit = "kWh", readings = listOf(Reading(value = 2.0, valueText = "2", billingPeriod = "2026-09")))
            )
        )

        val progress = MonthlyWalkPlanner.progress(address, period)

        assertTrue(progress.isComplete)
        assertEquals(setOf("cold", "power"), MonthlyWalkPlanner.completedMeterIds(address, period))
    }
}
