package ru.egor.meters

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Resolves the semantic billing period of a reading without depending on the
 * device's current timezone.
 *
 * New readings must persist billingPeriod explicitly. Timestamp conversion is
 * retained only for legacy rows that genuinely predate that field, and uses
 * UTC so changing the device timezone cannot move the same stored reading to a
 * different month later.
 */
object BillingPeriodResolver {
    fun readingPeriod(reading: Reading): YearMonth? {
        val stored = reading.billingPeriod
        if (stored != null) {
            return runCatching { YearMonth.parse(stored) }.getOrNull()
        }
        return legacyTimestampPeriod(reading.timestamp)
    }

    internal fun legacyTimestampPeriod(timestamp: Long): YearMonth? = runCatching {
        YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC))
    }.getOrNull()
}
