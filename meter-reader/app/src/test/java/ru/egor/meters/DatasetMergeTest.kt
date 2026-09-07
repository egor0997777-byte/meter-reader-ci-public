package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetMergeTest {
    private val originalReading = Reading(
        id = "r1",
        value = 10.0,
        valueText = "10",
        zoneValues = mapOf("TOTAL" to "10"),
        billingPeriod = "2026-09"
    )
    private val originalMeter = Meter(
        id = "m1",
        name = "Вода",
        unit = "м³",
        readings = listOf(originalReading),
        meteringPointId = "p1"
    )
    private val originalAddress = Address(id = "a1", name = "Дом", meters = listOf(originalMeter))

    @Test
    fun unrelatedStaleEditPreservesNewerReading() {
        val base = listOf(originalAddress)
        val liveReading = Reading(
            id = "r2",
            value = 11.0,
            valueText = "11",
            zoneValues = mapOf("TOTAL" to "11"),
            billingPeriod = "2026-10"
        )
        val current = listOf(originalAddress.copy(meters = listOf(originalMeter.copy(readings = listOf(originalReading, liveReading)))))
        val updated = listOf(originalAddress.copy(name = "Дом, новая подпись"))

        val merged = DatasetMerge.merge(base, updated, current)

        assertEquals("Дом, новая подпись", merged.single().name)
        assertEquals(listOf("r1", "r2"), merged.single().meters.single().readings.map { it.id })
    }

    @Test
    fun staleDeletionCannotRemoveReadingChangedElsewhere() {
        val base = listOf(originalAddress)
        val currentReading = originalReading.copy(note = "из другого экрана")
        val current = listOf(originalAddress.copy(meters = listOf(originalMeter.copy(readings = listOf(currentReading)))))
        val updated = listOf(originalAddress.copy(meters = listOf(originalMeter.copy(readings = emptyList()))))

        val failure = runCatching { DatasetMerge.merge(base, updated, current) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun concurrentEditOfSameFieldFailsClosed() {
        val base = listOf(originalAddress)
        val current = listOf(originalAddress.copy(name = "Изменено в обходе"))
        val updated = listOf(originalAddress.copy(name = "Изменено в истории"))

        val failure = runCatching { DatasetMerge.merge(base, updated, current) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun editingReadingPreservesCanonicalPeriodAndZonesUnlessExplicitlyChanged() {
        val base = listOf(originalAddress)
        val currentReading = originalReading.copy(note = "новая заметка")
        val current = listOf(originalAddress.copy(meters = listOf(originalMeter.copy(readings = listOf(currentReading)))))
        val updatedReading = originalReading.copy(photoUri = "content://photo")
        val updated = listOf(originalAddress.copy(meters = listOf(originalMeter.copy(readings = listOf(updatedReading)))))

        val mergedReading = DatasetMerge.merge(base, updated, current).single().meters.single().readings.single()

        assertEquals("2026-09", mergedReading.billingPeriod)
        assertEquals(mapOf("TOTAL" to "10"), mergedReading.zoneValues)
        assertEquals("новая заметка", mergedReading.note)
        assertEquals("content://photo", mergedReading.photoUri)
    }
}
