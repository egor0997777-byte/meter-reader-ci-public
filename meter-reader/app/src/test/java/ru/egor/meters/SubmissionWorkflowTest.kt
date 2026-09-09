package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class SubmissionWorkflowTest {
    @Test
    fun preparesOnlySelectedCurrentPeriodValues() {
        val period = YearMonth.of(2026, 9)
        val ts = period.atDay(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val water = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Холодная вода",
            unit = "м³",
            kind = "cold_water",
            readings = listOf(Reading(id = "r1", value = 12.345, valueText = "12.345", timestamp = ts, billingPeriod = period.toString(), zoneValues = mapOf("TOTAL" to "12.345")))
        )
        val electric = Meter(
            id = "m2",
            meteringPointId = "p2",
            name = "Электричество",
            unit = "кВт·ч",
            kind = "electricity",
            tariffZones = listOf("T1", "T2"),
            readings = listOf(Reading(id = "r2", value = 101.0, valueText = "101", timestamp = ts, billingPeriod = period.toString(), zoneValues = mapOf("T1" to "101", "T2" to "202")))
        )
        val address = Address(id = "a1", name = "Дом", meters = listOf(water, electric))
        val template = TransmissionTemplate(addressId = "a1", name = "Вода", account = "123", prefix = "Здравствуйте", suffix = "Спасибо", meteringPointIds = setOf("p1"))

        val prepared = SubmissionWorkflow.prepare(address, template, period)

        assertEquals(1, prepared.items.size)
        assertEquals("p1", prepared.items.single().meteringPointId)
        assertTrue(prepared.text.contains("Холодная вода: 12.345 м³"))
        assertTrue(!prepared.text.contains("Электричество"))
        assertTrue(prepared.missingPointIds.isEmpty())
    }

    @Test
    fun reportsMissingSelectedPointInsteadOfUsingOldReading() {
        val period = YearMonth.of(2026, 9)
        val oldTs = period.minusMonths(1).atDay(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val meter = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Газ",
            unit = "м³",
            readings = listOf(Reading(id = "old", value = 10.0, valueText = "10", timestamp = oldTs, billingPeriod = period.minusMonths(1).toString(), zoneValues = mapOf("TOTAL" to "10")))
        )
        val address = Address(id = "a1", name = "Дом", meters = listOf(meter))
        val prepared = SubmissionWorkflow.prepare(address, TransmissionTemplate(addressId = "a1", name = "Газ", meteringPointIds = setOf("p1")), period)

        assertTrue(prepared.items.isEmpty())
        assertEquals(setOf("p1"), prepared.missingPointIds)
    }

    @Test
    fun createsImmutableSubmissionSnapshotFromPreparedValues() {
        val period = YearMonth.of(2026, 9)
        val meter = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Вода",
            unit = "м³",
            readings = listOf(Reading(value = 15.2, valueText = "15.2", billingPeriod = period.toString(), zoneValues = mapOf("TOTAL" to "15.2")))
        )
        val address = Address(id = "a1", name = "Квартира", meters = listOf(meter))
        val template = TransmissionTemplate(id = "template-uk", addressId = "a1", name = "УК", recipient = "УК", meteringPointIds = setOf("p1"))
        val prepared = SubmissionWorkflow.prepare(address, template, period)

        val submission = SubmissionWorkflow.createSubmission(address, template, period, prepared, submittedAt = 1234L)

        assertEquals("2026-09", submission.billingPeriod)
        assertEquals(1234L, submission.submittedAt)
        assertEquals("УК", submission.recipient)
        assertEquals(prepared.text, submission.snapshotText)
        assertEquals("15.2", submission.items.single().valueText)
        assertTrue(SubmissionWorkflow.submissionBelongsToTemplate(submission.id, template.id))
    }

    @Test
    fun templateIdentityDoesNotCollideWhenRecipientsAreEqual() {
        val period = YearMonth.of(2026, 9)
        val meter = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Вода",
            unit = "м³",
            readings = listOf(Reading(value = 15.2, valueText = "15.2", billingPeriod = period.toString(), zoneValues = mapOf("TOTAL" to "15.2")))
        )
        val address = Address(id = "a1", name = "Квартира", meters = listOf(meter))
        val first = TransmissionTemplate(id = "template-a", addressId = "a1", name = "Первый", recipient = "УК", meteringPointIds = setOf("p1"))
        val second = TransmissionTemplate(id = "template-b", addressId = "a1", name = "Второй", recipient = "УК", meteringPointIds = setOf("p1"))

        val submission = SubmissionWorkflow.createSubmission(address, first, period)

        assertTrue(SubmissionWorkflow.submissionBelongsToTemplate(submission.id, first.id))
        assertFalse(SubmissionWorkflow.submissionBelongsToTemplate(submission.id, second.id))
    }

    @Test
    fun allowsExplicitPartialSubmissionAndKeepsMissingPointsOutOfSnapshot() {
        val period = YearMonth.of(2026, 9)
        val water = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Вода",
            unit = "м³",
            readings = listOf(Reading(value = 18.25, valueText = "18.25", billingPeriod = period.toString(), zoneValues = mapOf("TOTAL" to "18.25")))
        )
        val electricity = Meter(id = "m2", meteringPointId = "p2", name = "Электричество", unit = "кВт·ч")
        val address = Address(id = "a1", name = "Квартира", meters = listOf(water, electricity))
        val template = TransmissionTemplate(addressId = "a1", name = "Все показания", meteringPointIds = setOf("p1", "p2"))
        val prepared = SubmissionWorkflow.prepare(address, template, period)

        val submission = SubmissionWorkflow.createSubmission(address, template, period, prepared, submittedAt = 1234L)

        assertEquals(setOf("p2"), prepared.missingPointIds)
        assertEquals(setOf("p1"), submission.items.map { it.meteringPointId }.toSet())
        assertTrue(submission.snapshotText.contains("Вода: 18.25 м³"))
        assertTrue(!submission.snapshotText.contains("Электричество"))
    }

    @Test
    fun pointIsNotTreatedAsSubmittedUntilEveryRequiredTariffZoneWasSubmitted() {
        val required = setOf("p1" to "T1", "p1" to "T2", "p2" to "TOTAL")
        val partial = setOf("p1" to "T1", "p2" to "TOTAL")

        val submittedPoints = SubmissionWorkflow.fullySubmittedPointIds(required, partial)

        assertEquals(setOf("p2"), submittedPoints)
    }

    @Test
    fun pointBecomesSubmittedWhenAllRequiredTariffZonesArePresent() {
        val required = setOf("p1" to "T1", "p1" to "T2", "p1" to "T3")
        val submitted = setOf("p1" to "T1", "p1" to "T2", "p1" to "T3")

        assertEquals(setOf("p1"), SubmissionWorkflow.fullySubmittedPointIds(required, submitted))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesSubmissionWhenNoSelectedPointHasCurrentReading() {
        val period = YearMonth.of(2026, 9)
        val meter = Meter(id = "m1", meteringPointId = "p1", name = "Вода", unit = "м³")
        val address = Address(id = "a1", name = "Квартира", meters = listOf(meter))
        val template = TransmissionTemplate(addressId = "a1", name = "УК", meteringPointIds = setOf("p1"))
        val prepared = SubmissionWorkflow.prepare(address, template, period)

        SubmissionWorkflow.createSubmission(address, template, period, prepared)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesTemplateFromDifferentAddress() {
        val address = Address(id = "a1", name = "Квартира")
        SubmissionWorkflow.prepare(address, TransmissionTemplate(addressId = "a2", name = "Чужой"), YearMonth.of(2026, 9))
    }
}
