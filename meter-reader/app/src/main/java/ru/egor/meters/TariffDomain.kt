package ru.egor.meters

import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId

object MeterZones {
    val SINGLE = listOf("TOTAL")
    val TWO_TARIFF = listOf("T1", "T2")
    val THREE_TARIFF = listOf("T1", "T2", "T3")

    fun normalize(zones: Collection<String>): List<String> {
        val normalized = zones.map { it.trim().uppercase() }
            .filter { it == "TOTAL" || it.matches(Regex("T[1-9][0-9]*")) }
            .distinct()
        return when {
            normalized.isEmpty() -> SINGLE
            "TOTAL" in normalized -> SINGLE
            else -> normalized.sortedBy { it.removePrefix("T").toIntOrNull() ?: Int.MAX_VALUE }
        }
    }
}

data class ZoneReadingPoint(
    val timestamp: Long,
    val values: Map<String, String>
)

data class ZoneConsumption(
    val zone: String,
    val amount: BigDecimal
)

data class PeriodComparison(
    val zone: String,
    val current: BigDecimal,
    val previous: BigDecimal?,
    val delta: BigDecimal?
)

data class TariffScheduleEntry(
    val zone: String,
    val validFrom: Long,
    val priceText: String
)

data class CostInterval(
    val zone: String,
    val fromTimestamp: Long,
    val toTimestamp: Long,
    val consumption: BigDecimal,
    val priceText: String,
    val amount: BigDecimal
)

data class CostEstimate(
    val total: BigDecimal,
    val intervals: List<CostInterval>
)

object ZoneAnalytics {
    fun consumptionBetween(
        previous: ZoneReadingPoint,
        current: ZoneReadingPoint,
        zones: Collection<String>
    ): List<ZoneConsumption> = MeterZones.normalize(zones).mapNotNull { zone ->
        val before = decimal(previous.values[zone]) ?: return@mapNotNull null
        val after = decimal(current.values[zone]) ?: return@mapNotNull null
        if (after < before) return@mapNotNull null
        ZoneConsumption(zone, after.subtract(before))
    }

    fun monthlyConsumption(
        readings: List<ZoneReadingPoint>,
        month: YearMonth,
        zones: Collection<String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<ZoneConsumption> {
        val ordered = readings.sortedBy { it.timestamp }
        if (ordered.size < 2) return emptyList()
        val endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val lastAtOrBeforeEnd = ordered.lastOrNull { it.timestamp < endExclusive } ?: return emptyList()
        val previousBeforeMonth = ordered.lastOrNull { it.timestamp < start }
        val firstInMonth = ordered.firstOrNull { it.timestamp >= start && it.timestamp < endExclusive }
        val baseline = previousBeforeMonth ?: firstInMonth ?: return emptyList()
        if (baseline.timestamp == lastAtOrBeforeEnd.timestamp) return emptyList()
        return consumptionBetween(baseline, lastAtOrBeforeEnd, zones)
    }

    fun comparePeriods(current: List<ZoneConsumption>, previous: List<ZoneConsumption>): List<PeriodComparison> {
        val previousByZone = previous.associateBy { it.zone }
        return current.map { item ->
            val old = previousByZone[item.zone]?.amount
            PeriodComparison(item.zone, item.amount, old, old?.let { item.amount.subtract(it) })
        }
    }

    fun shouldShowGraph(pointCount: Int): Boolean = pointCount >= 6

    fun activeTariff(
        schedule: List<TariffScheduleEntry>,
        zone: String,
        atMillis: Long
    ): TariffScheduleEntry? = schedule
        .asSequence()
        .filter { it.zone.equals(zone, ignoreCase = true) && it.validFrom <= atMillis }
        .maxByOrNull { it.validFrom }

    fun estimateCost(
        consumption: List<ZoneConsumption>,
        schedule: List<TariffScheduleEntry>,
        atMillis: Long
    ): BigDecimal? {
        var total = BigDecimal.ZERO
        for (item in consumption) {
            val tariff = activeTariff(schedule, item.zone, atMillis) ?: return null
            val price = validPrice(tariff.priceText) ?: return null
            total = total.add(item.amount.multiply(price))
        }
        return total
    }

    /**
     * Reproducible estimate based only on adjacent saved readings. Each interval is charged
     * with the tariff valid on the date of the interval's later reading. We deliberately do
     * not interpolate consumption across a tariff-change date because the app cannot know
     * when consumption happened between two cumulative readings.
     */
    fun estimateCostFromHistory(
        readings: List<ZoneReadingPoint>,
        zones: Collection<String>,
        schedule: List<TariffScheduleEntry>,
        fromInclusive: Long,
        toExclusive: Long
    ): CostEstimate? {
        if (toExclusive <= fromInclusive || schedule.isEmpty()) return null
        val expectedZones = MeterZones.normalize(zones)
        val ordered = readings.sortedBy { it.timestamp }
        if (ordered.size < 2) return null

        val intervals = mutableListOf<CostInterval>()
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            if (current.timestamp < fromInclusive || current.timestamp >= toExclusive) continue

            val consumption = consumptionBetween(previous, current, expectedZones)
            if (consumption.size != expectedZones.size) return null
            for (item in consumption) {
                val tariff = activeTariff(schedule, item.zone, current.timestamp) ?: return null
                val price = validPrice(tariff.priceText) ?: return null
                intervals += CostInterval(
                    zone = item.zone,
                    fromTimestamp = previous.timestamp,
                    toTimestamp = current.timestamp,
                    consumption = item.amount,
                    priceText = tariff.priceText,
                    amount = item.amount.multiply(price)
                )
            }
        }
        if (intervals.isEmpty()) return null
        return CostEstimate(intervals.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.amount) }, intervals)
    }

    fun estimateMonthlyCost(
        readings: List<ZoneReadingPoint>,
        month: YearMonth,
        zones: Collection<String>,
        schedule: List<TariffScheduleEntry>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CostEstimate? {
        val from = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return estimateCostFromHistory(readings, zones, schedule, from, to)
    }

    fun normalizePriceText(raw: String): String? {
        val cleaned = raw.trim().replace(',', '.')
        val value = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
        if (value < BigDecimal.ZERO) return null
        return cleaned
    }

    private fun validPrice(value: String): BigDecimal? = normalizePriceText(value)?.let { BigDecimal(it) }

    private fun decimal(value: String?): BigDecimal? = value
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }
}
