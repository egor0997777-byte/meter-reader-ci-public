package ru.egor.meters

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffDomainTest {
    private val zone = ZoneId.of("Europe/Moscow")

    private fun ts(y: Int, m: Int, d: Int): Long =
        LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun normalizesSingleAndMultiTariffZones() {
        assertEquals(listOf("TOTAL"), MeterZones.normalize(emptyList()))
        assertEquals(listOf("TOTAL"), MeterZones.normalize(listOf("TOTAL", "T1")))
        assertEquals(listOf("T1", "T2", "T3"), MeterZones.normalize(listOf("t3", "T1", "T2", "T2")))
    }

    @Test fun computesConsumptionIndependentlyPerZone() {
        val previous = ZoneReadingPoint(1, mapOf("T1" to "100.5", "T2" to "200"))
        val current = ZoneReadingPoint(2, mapOf("T1" to "112.75", "T2" to "205.5"))
        assertEquals(
            listOf(
                ZoneConsumption("T1", BigDecimal("12.25")),
                ZoneConsumption("T2", BigDecimal("5.5"))
            ),
            ZoneAnalytics.consumptionBetween(previous, current, listOf("T1", "T2"))
        )
    }

    @Test fun rejectsBackwardZoneWithoutInventingConsumption() {
        val previous = ZoneReadingPoint(1, mapOf("T1" to "100", "T2" to "200"))
        val current = ZoneReadingPoint(2, mapOf("T1" to "99", "T2" to "210"))
        assertEquals(listOf(ZoneConsumption("T2", BigDecimal("10"))), ZoneAnalytics.consumptionBetween(previous, current, listOf("T1", "T2")))
    }

    @Test fun calculatesMonthlyConsumptionFromPreviousKnownReading() {
        val readings = listOf(
            ZoneReadingPoint(ts(2026, 7, 31), mapOf("T1" to "100", "T2" to "200")),
            ZoneReadingPoint(ts(2026, 8, 15), mapOf("T1" to "115", "T2" to "208")),
            ZoneReadingPoint(ts(2026, 8, 31), mapOf("T1" to "130", "T2" to "220"))
        )
        assertEquals(
            listOf(ZoneConsumption("T1", BigDecimal("30")), ZoneConsumption("T2", BigDecimal("20"))),
            ZoneAnalytics.monthlyConsumption(readings, YearMonth.of(2026, 8), listOf("T1", "T2"), zone)
        )
    }

    @Test fun comparesCurrentAndPreviousPeriodByZone() {
        val current = listOf(ZoneConsumption("T1", BigDecimal("30")), ZoneConsumption("T2", BigDecimal("20")))
        val previous = listOf(ZoneConsumption("T1", BigDecimal("25")), ZoneConsumption("T2", BigDecimal("22")))
        assertEquals(
            listOf(
                PeriodComparison("T1", BigDecimal("30"), BigDecimal("25"), BigDecimal("5")),
                PeriodComparison("T2", BigDecimal("20"), BigDecimal("22"), BigDecimal("-2"))
            ),
            ZoneAnalytics.comparePeriods(current, previous)
        )
    }

    @Test fun graphRequiresSixPoints() {
        assertFalse(ZoneAnalytics.shouldShowGraph(5))
        assertTrue(ZoneAnalytics.shouldShowGraph(6))
    }

    @Test fun tariffScheduleUsesLatestEntryValidAtDate() {
        val schedule = listOf(
            TariffScheduleEntry("T1", ts(2026, 1, 1), "5.00"),
            TariffScheduleEntry("T1", ts(2026, 7, 1), "6.25")
        )
        assertEquals("5.00", ZoneAnalytics.activeTariff(schedule, "T1", ts(2026, 6, 30))?.priceText)
        assertEquals("6.25", ZoneAnalytics.activeTariff(schedule, "T1", ts(2026, 8, 1))?.priceText)
        assertNull(ZoneAnalytics.activeTariff(schedule, "T2", ts(2026, 8, 1)))
    }

    @Test fun costRequiresTariffsForEveryZone() {
        val consumption = listOf(ZoneConsumption("T1", BigDecimal("10")), ZoneConsumption("T2", BigDecimal("5")))
        val full = listOf(
            TariffScheduleEntry("T1", ts(2026, 1, 1), "6"),
            TariffScheduleEntry("T2", ts(2026, 1, 1), "3")
        )
        assertEquals(BigDecimal("75"), ZoneAnalytics.estimateCost(consumption, full, ts(2026, 8, 1)))
        assertNull(ZoneAnalytics.estimateCost(consumption, full.filter { it.zone == "T1" }, ts(2026, 8, 1)))
    }

    @Test fun monthlyCostUsesTariffValidForEachRecordedInterval() {
        val readings = listOf(
            ZoneReadingPoint(ts(2026, 7, 31), mapOf("TOTAL" to "100")),
            ZoneReadingPoint(ts(2026, 8, 10), mapOf("TOTAL" to "110")),
            ZoneReadingPoint(ts(2026, 8, 25), mapOf("TOTAL" to "120"))
        )
        val schedule = listOf(
            TariffScheduleEntry("TOTAL", ts(2026, 1, 1), "5"),
            TariffScheduleEntry("TOTAL", ts(2026, 8, 15), "7")
        )

        val estimate = ZoneAnalytics.estimateMonthlyCost(readings, YearMonth.of(2026, 8), MeterZones.SINGLE, schedule, zone)
        assertEquals(BigDecimal("120"), estimate?.total)
        assertEquals(listOf("5", "7"), estimate?.intervals?.map { it.priceText })
        assertEquals(listOf(BigDecimal("50"), BigDecimal("70")), estimate?.intervals?.map { it.amount })
    }

    @Test fun multitariffMonthlyCostUsesIndependentZonePrices() {
        val readings = listOf(
            ZoneReadingPoint(ts(2026, 7, 31), mapOf("T1" to "100", "T2" to "200", "T3" to "300")),
            ZoneReadingPoint(ts(2026, 8, 31), mapOf("T1" to "110", "T2" to "205", "T3" to "302"))
        )
        val schedule = listOf(
            TariffScheduleEntry("T1", ts(2026, 1, 1), "6"),
            TariffScheduleEntry("T2", ts(2026, 1, 1), "3"),
            TariffScheduleEntry("T3", ts(2026, 1, 1), "2")
        )

        val estimate = ZoneAnalytics.estimateMonthlyCost(readings, YearMonth.of(2026, 8), MeterZones.THREE_TARIFF, schedule, zone)
        assertEquals(BigDecimal("79"), estimate?.total)
        assertEquals(3, estimate?.intervals?.size)
    }

    @Test fun historyCostIsUnavailableWhenTariffOrZoneValueIsMissing() {
        val readings = listOf(
            ZoneReadingPoint(ts(2026, 7, 31), mapOf("T1" to "100", "T2" to "200")),
            ZoneReadingPoint(ts(2026, 8, 31), mapOf("T1" to "110", "T2" to "205"))
        )
        val incomplete = listOf(TariffScheduleEntry("T1", ts(2026, 1, 1), "6"))
        assertNull(ZoneAnalytics.estimateMonthlyCost(readings, YearMonth.of(2026, 8), MeterZones.TWO_TARIFF, incomplete, zone))
        assertNull(ZoneAnalytics.estimateMonthlyCost(readings.map { it.copy(values = it.values - "T2") }, YearMonth.of(2026, 8), MeterZones.TWO_TARIFF, listOf(
            TariffScheduleEntry("T1", ts(2026, 1, 1), "6"),
            TariffScheduleEntry("T2", ts(2026, 1, 1), "3")
        ), zone))
    }

    @Test fun priceNormalizationRejectsNegativeAndAcceptsComma() {
        assertEquals("6.25", ZoneAnalytics.normalizePriceText(" 6,25 "))
        assertNull(ZoneAnalytics.normalizePriceText("-1"))
        assertNull(ZoneAnalytics.normalizePriceText("abc"))
    }
}
