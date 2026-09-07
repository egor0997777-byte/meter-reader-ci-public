package ru.egor.meters

/**
 * Compose state maps return nullable values even when the map value type is String.
 * Keep the strict String parser as the source of truth and treat a missing UI field as invalid.
 */
internal fun MeterHistory.normalizeReadingText(raw: String?): String? =
    raw?.let { normalizeReadingText(it) }
