package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterReplacementTest {
    @Test fun replacementKeepsOldHistorySeparate() {
        val old = Meter(
            id = "old",
            name = "Холодная вода",
            unit = "м³",
            kind = "cold_water",
            readings = listOf(Reading(id = "r1", value = 120.0, timestamp = 1L)),
            integerDigits = 5,
            fractionDigits = 3,
            status = "closed"
        )
        val replacement = Meter(
            id = "new",
            name = "Холодная вода",
            unit = "м³",
            kind = "cold_water",
            readings = listOf(Reading(id = "r2", value = 0.0, timestamp = 2L)),
            integerDigits = 5,
            fractionDigits = 3,
            previousMeterId = old.id,
            installedAt = 2L,
            status = "active"
        )

        assertEquals(old.id, replacement.previousMeterId)
        assertNotEquals(old.id, replacement.id)
        assertEquals(1, old.readings.size)
        assertEquals("r1", old.readings.single().id)
        assertTrue(old.status == "closed" && replacement.status == "active")
    }
}
