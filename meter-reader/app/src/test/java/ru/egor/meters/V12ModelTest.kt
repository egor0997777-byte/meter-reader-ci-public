package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class V12ModelTest {
    @Test
    fun canonicalReadingTextDoesNotDependOnDoublePrecision() {
        val exact = "12345678901234567890.123456789"
        val reading = Reading(
            value = exact.toDouble(),
            valueText = exact,
            zoneValues = mapOf("TOTAL" to exact),
            billingPeriod = "2026-08"
        )

        assertEquals(exact, reading.valueText)
        assertEquals(exact, reading.zoneValues["TOTAL"])
        assertNotEquals(exact, reading.value.toString())
    }

    @Test
    fun submittedSnapshotDoesNotChangeWhenReadingIsEditedLater() {
        val pointId = "point-water-bathroom"
        val meterId = "meter-1"
        val original = Reading(
            value = 100.125,
            valueText = "100.125",
            zoneValues = mapOf("TOTAL" to "100.125"),
            billingPeriod = "2026-08"
        )
        val submission = Submission(
            addressId = "home",
            billingPeriod = "2026-08",
            items = listOf(SubmissionItem(pointId, meterId, "TOTAL", original.valueText!!))
        )

        val edited = original.copy(value = 101.500, valueText = "101.500", zoneValues = mapOf("TOTAL" to "101.500"))

        assertEquals("101.500", edited.valueText)
        assertEquals("100.125", submission.items.single().valueText)
    }

    @Test
    fun replacementDevicesCanBelongToSameLogicalMeteringPoint() {
        val pointId = "point-electricity"
        val old = Meter(name = "Электричество", unit = "кВт·ч", id = "old", meteringPointId = pointId, status = "closed")
        val replacement = Meter(
            name = "Электричество",
            unit = "кВт·ч",
            id = "new",
            previousMeterId = old.id,
            meteringPointId = pointId,
            status = "active"
        )

        assertEquals(pointId, old.meteringPointId)
        assertEquals(pointId, replacement.meteringPointId)
        assertEquals(old.id, replacement.previousMeterId)
    }
}
