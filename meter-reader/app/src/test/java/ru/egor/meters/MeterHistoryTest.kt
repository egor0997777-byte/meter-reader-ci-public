package ru.egor.meters

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterHistoryTest {
    private fun assertAmount(expected: String, actual: BigDecimal?) {
        requireNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test fun normalizesCommaAndKeepsLeadingZeros() {
        assertEquals("000012.340", MeterHistory.normalizeReadingText("000012,340"))
    }

    @Test fun exactDisplayKeepsPersistedLeadingZeros() {
        val reading = Reading(value = 12.0, valueText = "000012")
        assertEquals("000012", MeterHistory.displayText(reading))
    }

    @Test fun rejectsMalformedReading() {
        assertNull(MeterHistory.normalizeReadingText("12..3"))
        assertNull(MeterHistory.normalizeReadingText("-1"))
    }

    @Test fun computesNormalConsumption() {
        val r = MeterHistory.consumption("100.5", "112.75", 6)
        assertAmount("12.25", r.amount)
        assertFalse(r.rollover)
        assertFalse(r.lowerThanPrevious)
    }

    @Test fun equalReadingsProduceZeroConsumption() {
        val r = MeterHistory.consumption("12", "12", 6)
        assertAmount("0", r.amount)
    }

    @Test fun lowerReadingRequiresExplicitRollover() {
        val r = MeterHistory.consumption("999999", "000012", 6)
        assertNull(r.amount)
        assertTrue(r.lowerThanPrevious)
    }

    @Test fun mechanicalRolloverIsComputedWhenConfirmed() {
        val r = MeterHistory.consumption("999999", "000012", 6, allowRollover = true)
        assertAmount("13", r.amount)
        assertTrue(r.rollover)
    }

    @Test fun rolloverFlagOnReadingDrivesHistoryComputation() {
        val readings = listOf(
            Reading(id = "a", value = 999999.0, valueText = "999999", timestamp = 1L),
            Reading(id = "b", value = 12.0, valueText = "000012", timestamp = 2L, rollover = true)
        )
        val history = MeterHistory.history(readings, 6)
        assertAmount("13", history[1].consumption?.amount)
        assertTrue(history[1].consumption?.rollover == true)
    }

    @Test fun skippedMonthStillUsesAdjacentHistoryRecords() {
        val readings = listOf(
            Reading(id = "a", value = 100.0, timestamp = 1L),
            Reading(id = "b", value = 160.0, timestamp = 90L)
        )
        val history = MeterHistory.history(readings, integerDigits = 6)
        assertAmount("60", history[1].consumption?.amount)
    }

    @Test fun outOfOrderInputIsSortedByTimestamp() {
        val readings = listOf(
            Reading(id = "c", value = 150.0, timestamp = 30L),
            Reading(id = "a", value = 100.0, timestamp = 10L),
            Reading(id = "b", value = 120.0, timestamp = 20L)
        )
        val history = MeterHistory.history(readings, 6)
        assertEquals(listOf("a", "b", "c"), history.map { it.reading.id })
        assertAmount("20", history[1].consumption?.amount)
        assertAmount("30", history[2].consumption?.amount)
    }

    @Test fun editingOldReadingRecalculatesFollowingConsumption() {
        val before = listOf(
            Reading(id = "a", value = 100.0, timestamp = 1L),
            Reading(id = "b", value = 130.0, timestamp = 2L),
            Reading(id = "c", value = 150.0, timestamp = 3L)
        )
        val after = before.map { if (it.id == "b") it.copy(value = 140.0) else it }
        assertAmount("30", MeterHistory.history(before, 6)[1].consumption?.amount)
        assertAmount("20", MeterHistory.history(before, 6)[2].consumption?.amount)
        assertAmount("40", MeterHistory.history(after, 6)[1].consumption?.amount)
        assertAmount("10", MeterHistory.history(after, 6)[2].consumption?.amount)
    }

    @Test fun deletingMiddleReadingRecalculatesNextAgainstEarlier() {
        val before = listOf(
            Reading(id = "a", value = 100.0, timestamp = 1L),
            Reading(id = "b", value = 130.0, timestamp = 2L),
            Reading(id = "c", value = 150.0, timestamp = 3L)
        )
        val after = before.filterNot { it.id == "b" }
        assertAmount("50", MeterHistory.history(after, 6)[1].consumption?.amount)
    }

    @Test fun arbitraryPeriodUsesHistoryBoundaries() {
        val readings = listOf(
            Reading(id = "a", value = 100.0, timestamp = 10L),
            Reading(id = "b", value = 120.0, timestamp = 20L),
            Reading(id = "c", value = 155.0, timestamp = 30L)
        )
        val result = MeterHistory.consumptionForPeriod(readings, 10L, 30L, 6)
        assertAmount("55", result?.amount)
    }

    @Test fun anomalyNeedsEnoughHistoryAndUsesMedian() {
        assertFalse(MeterHistory.isAnomalous(BigDecimal("100"), listOf(BigDecimal("10"))))
        assertTrue(MeterHistory.isAnomalous(BigDecimal("40"), listOf(BigDecimal("10"), BigDecimal("11"), BigDecimal("12"))))
    }
}
