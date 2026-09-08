package ru.egor.meters

import java.time.YearMonth

data class MonthlyAddressProgress(
    val activeCount: Int,
    val completedCount: Int
) {
    val remainingCount: Int get() = (activeCount - completedCount).coerceAtLeast(0)
    val isComplete: Boolean get() = activeCount > 0 && remainingCount == 0
}

/** Pure monthly-flow decisions shared by the launcher hub and app shortcut. */
object MonthlyWalkPlanner {
    fun progress(address: Address, period: YearMonth): MonthlyAddressProgress {
        val active = address.meters.filter { it.status != "closed" }
        val completed = active.count { hasUsableReading(it, period) }
        return MonthlyAddressProgress(active.size, completed)
    }

    fun completedMeterIds(address: Address, period: YearMonth): Set<String> =
        address.meters
            .asSequence()
            .filter { it.status != "closed" }
            .filter { hasUsableReading(it, period) }
            .map { it.id }
            .toSet()

    private fun hasUsableReading(meter: Meter, period: YearMonth): Boolean {
        val reading = meter.readings
            .filter { BillingPeriodResolver.readingPeriod(it) == period }
            .maxByOrNull { it.timestamp }
            ?: return false
        val sourceValues = reading.zoneValues.takeIf { it.isNotEmpty() }
            ?: mapOf("TOTAL" to MeterHistory.readingText(reading))
        return ReadingValidator.normalizeForMeter(sourceValues, meter) != null
    }
}
