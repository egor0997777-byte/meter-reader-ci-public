package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SubmissionEditIsolationTest {
    @Test
    fun submittedSnapshotDoesNotChangeWhenReadingIsEditedLater() {
        val period = YearMonth.of(2026, 9)
        val original = Reading(
            id = "reading-september",
            value = 10.0,
            valueText = "00010",
            zoneValues = mapOf("TOTAL" to "00010"),
            billingPeriod = period.toString()
        )
        val meter = Meter(
            id = "device-1",
            name = "Холодная вода",
            unit = "м³",
            kind = "cold_water",
            integerDigits = 5,
            fractionDigits = 0,
            meteringPointId = "point-1",
            readings = listOf(original)
        )
        val address = Address(id = "home", name = "Дом", meters = listOf(meter))
        val template = TransmissionTemplate(
            id = "uk-template",
            addressId = address.id,
            name = "УК",
            recipient = "УК",
            meteringPointIds = setOf("point-1")
        )

        val preparedAtSubmit = SubmissionWorkflow.prepare(address, template, period)
        val submitted = SubmissionWorkflow.createSubmission(address, template, period, preparedAtSubmit)

        val edited = original.copy(
            value = 11.0,
            valueText = "00011",
            zoneValues = mapOf("TOTAL" to "00011")
        )
        val editedAddress = address.copy(
            meters = listOf(meter.copy(readings = listOf(edited)))
        )
        val preparedAfterEdit = SubmissionWorkflow.prepare(editedAddress, template, period)

        assertEquals("00010", submitted.items.single().valueText)
        assertTrue(submitted.snapshotText.contains("00010"))
        assertEquals("00011", preparedAfterEdit.items.single().valueText)
        assertTrue(preparedAfterEdit.text.contains("00011"))
        assertNotEquals(submitted.snapshotText, preparedAfterEdit.text)
        assertEquals(period.toString(), submitted.billingPeriod)
        assertEquals("УК", submitted.recipient)
    }
}
