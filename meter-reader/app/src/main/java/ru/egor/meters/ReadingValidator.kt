package ru.egor.meters

/**
 * Canonical validation for persisted/manual reading text.
 *
 * The normalized String is the source of truth. Double remains a compatibility/display field in
 * the current schema and must never be used to reconstruct new canonical persisted text.
 */
object ReadingValidator {
    private val supportedZoneSets = setOf(
        setOf("TOTAL"),
        setOf("T1", "T2"),
        setOf("T1", "T2", "T3")
    )

    data class Digits(val integer: Int, val fraction: Int)

    fun effectiveDigits(meter: Meter): Digits {
        val defaults = when (meter.kind) {
            "cold_water", "hot_water", "gas" -> Digits(5, 3)
            "electricity" -> Digits(6, 1)
            "heating" -> Digits(6, 3)
            else -> Digits(6, 2)
        }
        return Digits(
            integer = meter.integerDigits ?: defaults.integer,
            fraction = meter.fractionDigits ?: defaults.fraction
        )
    }

    fun normalizeValue(raw: String?, digits: Digits): String? {
        if (digits.integer !in 1..18 || digits.fraction !in 0..12) return null
        val normalized = MeterHistory.normalizeReadingText(raw) ?: return null
        val parts = normalized.split('.', limit = 2)
        val integerPart = parts[0]
        val fractionPart = parts.getOrNull(1)
        if (integerPart.length > digits.integer) return null
        if (fractionPart != null && fractionPart.length > digits.fraction) return null
        if (digits.fraction == 0 && fractionPart != null) return null
        return normalized
    }

    fun normalizeValues(
        rawValues: Map<String, String>,
        digits: Digits,
        expectedZones: Collection<String>? = null
    ): LinkedHashMap<String, String>? {
        val normalizedKeys = rawValues.keys.map { it.trim().uppercase() }.toSet()
        if (normalizedKeys !in supportedZoneSets) return null
        expectedZones?.let {
            val expected = MeterZones.normalize(it).toSet()
            if (normalizedKeys != expected) return null
        }
        val result = linkedMapOf<String, String>()
        val orderedZones = when (normalizedKeys) {
            setOf("TOTAL") -> listOf("TOTAL")
            setOf("T1", "T2") -> listOf("T1", "T2")
            setOf("T1", "T2", "T3") -> listOf("T1", "T2", "T3")
            else -> return null
        }
        for (zone in orderedZones) {
            val source = rawValues.entries.firstOrNull { it.key.trim().uppercase() == zone }?.value ?: return null
            result[zone] = normalizeValue(source, digits) ?: return null
        }
        return result
    }

    fun normalizeForMeter(rawValues: Map<String, String>, meter: Meter): LinkedHashMap<String, String>? =
        normalizeValues(rawValues, effectiveDigits(meter), MeterZones.normalize(meter.tariffZones))
}
