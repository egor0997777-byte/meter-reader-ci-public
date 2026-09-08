package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.util.TimeZone

class BillingPeriodResolverTest {
    @Test
    fun explicitBillingPeriodWinsOverTimestamp() {
        val reading = Reading(
            value = 1.0,
            valueText = "1",
            timestamp = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(),
            billingPeriod = "2026-09"
        )

        assertEquals(YearMonth.of(2026, 9), BillingPeriodResolver.readingPeriod(reading))
    }

    @Test
    fun malformedExplicitPeriodIsNotSilentlyReinterpretedFromTimestamp() {
        val reading = Reading(
            value = 1.0,
            valueText = "1",
            timestamp = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli(),
            billingPeriod = "September 2026"
        )

        assertNull(BillingPeriodResolver.readingPeriod(reading))
    }

    @Test
    fun legacyTimestampPeriodDoesNotChangeWithDeviceTimezone() {
        val reading = Reading(
            value = 1.0,
            valueText = "1",
            timestamp = Instant.parse("2026-09-01T00:30:00Z").toEpochMilli(),
            billingPeriod = null
        )
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val hawaii = BillingPeriodResolver.readingPeriod(reading)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val kiritimati = BillingPeriodResolver.readingPeriod(reading)

            assertEquals(YearMonth.of(2026, 9), hawaii)
            assertEquals(hawaii, kiritimati)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
