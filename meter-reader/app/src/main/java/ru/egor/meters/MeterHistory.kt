package ru.egor.meters

import java.math.BigDecimal

data class ConsumptionResult(
    val amount: BigDecimal?,
    val rollover: Boolean = false,
    val lowerThanPrevious: Boolean = false
)

data class HistoryPoint(
    val reading: Reading,
    val consumption: ConsumptionResult?
)

object MeterHistory {
    fun normalizeReadingText(raw: String?): String? {
        val cleaned = raw?.trim()?.replace(',', '.') ?: return null
        if (!cleaned.matches(Regex("\\d+(?:\\.\\d+)?"))) return null
        return cleaned
    }

    fun parse(raw: String?): BigDecimal? = normalizeReadingText(raw)?.toBigDecimalOrNull()

    fun readingText(reading: Reading): String =
        reading.valueText ?: decimalText(reading.value)

    fun consumption(
        previous: String,
        current: String,
        integerDigits: Int,
        allowRollover: Boolean = false
    ): ConsumptionResult {
        val prev = parse(previous) ?: return ConsumptionResult(null)
        val cur = parse(current) ?: return ConsumptionResult(null)
        if (cur >= prev) return ConsumptionResult(cur - prev)
        if (!allowRollover || integerDigits <= 0) {
            return ConsumptionResult(null, lowerThanPrevious = true)
        }
        val modulus = BigDecimal.TEN.pow(integerDigits)
        if (prev >= modulus || cur >= modulus) {
            return ConsumptionResult(null, lowerThanPrevious = true)
        }
        return ConsumptionResult(modulus - prev + cur, rollover = true)
    }

    fun history(readings: List<Reading>, integerDigits: Int): List<HistoryPoint> {
        val sorted = readings.sortedWith(compareBy<Reading> { it.timestamp }.thenBy { it.id })
        return sorted.mapIndexed { index, reading ->
            val previous = sorted.getOrNull(index - 1)
            HistoryPoint(
                reading = reading,
                consumption = previous?.let {
                    consumption(
                        previous = readingText(it),
                        current = readingText(reading),
                        integerDigits = integerDigits,
                        allowRollover = reading.rollover
                    )
                }
            )
        }
    }

    fun consumptionForPeriod(
        readings: List<Reading>,
        fromTimestamp: Long,
        toTimestamp: Long,
        integerDigits: Int,
        allowRollover: Boolean = false
    ): ConsumptionResult? {
        if (toTimestamp < fromTimestamp) return null
        val sorted = readings.sortedBy { it.timestamp }
        val first = sorted.lastOrNull { it.timestamp <= fromTimestamp }
            ?: sorted.firstOrNull { it.timestamp >= fromTimestamp }
            ?: return null
        val last = sorted.lastOrNull { it.timestamp <= toTimestamp } ?: return null
        if (last.timestamp < first.timestamp) return null
        return consumption(readingText(first), readingText(last), integerDigits, allowRollover)
    }

    fun isAnomalous(amount: BigDecimal, recentAmounts: List<BigDecimal>): Boolean {
        val positive = recentAmounts.filter { it > BigDecimal.ZERO }.sorted()
        if (positive.size < 3) return false
        val median = positive[positive.size / 2]
        return amount > median.multiply(BigDecimal("3"))
    }

    fun decimalText(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    fun displayText(reading: Reading): String = readingText(reading)

    fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
