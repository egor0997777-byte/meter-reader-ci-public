package ru.egor.meters

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ReadingSubmissionValidationTest {
    @Test
    fun overlongCanonicalValueCannotBecomeSubmissionItem() {
        val period = YearMonth.of(2026, 9)
        val meter = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Электричество",
            unit = "кВт·ч",
            kind = "electricity",
            integerDigits = 6,
            fractionDigits = 1,
            tariffZones = MeterZones.SINGLE,
            readings = listOf(
                Reading(
                    id = "r1",
                    value = 1234567.0,
                    valueText = "1234567.0",
                    zoneValues = mapOf("TOTAL" to "1234567.0"),
                    billingPeriod = period.toString()
                )
            )
        )
        val address = Address(id = "a1", name = "Дом", meters = listOf(meter))
        val template = TransmissionTemplate(addressId = address.id, name = "Получатель", meteringPointIds = setOf("p1"))

        val prepared = SubmissionWorkflow.prepare(address, template, period)

        assertTrue(prepared.items.isEmpty())
        assertTrue("p1" in prepared.missingPointIds)
    }

    @Test
    fun incompleteThreeTariffValueCannotBecomeSubmissionItem() {
        val period = YearMonth.of(2026, 9)
        val meter = Meter(
            id = "m1",
            meteringPointId = "p1",
            name = "Электричество",
            unit = "кВт·ч",
            kind = "electricity",
            integerDigits = 6,
            fractionDigits = 1,
            tariffZones = MeterZones.THREE_TARIFF,
            readings = listOf(
                Reading(
                    id = "r1",
                    value = 100.0,
                    valueText = "100.0",
                    zoneValues = mapOf("T1" to "100.0", "T2" to "50.0"),
                    billingPeriod = period.toString()
                )
            )
        )
        val address = Address(id = "a1", name = "Дом", meters = listOf(meter))
        val template = TransmissionTemplate(addressId = address.id, name = "Получатель", meteringPointIds = setOf("p1"))

        val prepared = SubmissionWorkflow.prepare(address, template, period)

        assertTrue(prepared.items.isEmpty())
        assertTrue("p1" in prepared.missingPointIds)
    }
}
