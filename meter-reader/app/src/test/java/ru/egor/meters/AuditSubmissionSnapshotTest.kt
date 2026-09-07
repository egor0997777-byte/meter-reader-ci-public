package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditSubmissionSnapshotTest {
    @Test
    fun v4BackupKeepsHistoricalSubmissionWhenOriginalMeterWasDeleted() {
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m-new",
                    name = "Холодная вода",
                    unit = "м³",
                    meteringPointId = "p1",
                    readings = listOf(
                        Reading(
                            id = "r-new",
                            value = 15.0,
                            valueText = "00015.000",
                            zoneValues = mapOf("TOTAL" to "00015.000"),
                            billingPeriod = "2026-09"
                        )
                    )
                )
            )
        )
        val historical = Submission(
            id = "s-old",
            addressId = "a1",
            billingPeriod = "2026-08",
            recipient = "УК",
            snapshotText = "ХВС: 00014.000",
            items = listOf(SubmissionItem("p1", "m-old", "TOTAL", "00014.000"))
        )

        val parsed = MeterTransfer.parseBackup(
            MeterTransfer.createBackupBytes(listOf(address), listOf(historical))
        )

        assertEquals("m-old", parsed.submissions.single().items.single().meterId)
        assertEquals("00014.000", parsed.submissions.single().items.single().valueText)
    }

    @Test
    fun v4BackupRejectsHistoricalItemPointOwnedByAnotherAddress() {
        val addresses = listOf(
            Address(
                id = "a1",
                name = "Дом 1",
                meters = listOf(Meter(id = "m1", name = "Вода", unit = "м³", meteringPointId = "p1"))
            ),
            Address(
                id = "a2",
                name = "Дом 2",
                meters = listOf(Meter(id = "m2", name = "Газ", unit = "м³", meteringPointId = "p2"))
            )
        )
        val invalid = Submission(
            id = "s1",
            addressId = "a1",
            billingPeriod = "2026-09",
            items = listOf(SubmissionItem("p2", "m-deleted", "TOTAL", "1"))
        )

        assertTrue(
            runCatching { MeterTransfer.createBackupBytes(addresses, listOf(invalid)) }.isFailure
        )
    }

    @Test
    fun partialPreparedSubmissionRemainsPartialUntilMissingPointIsSent() {
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m1",
                    name = "Вода",
                    unit = "м³",
                    meteringPointId = "p1",
                    readings = listOf(
                        Reading(
                            id = "r1",
                            value = 10.0,
                            valueText = "10",
                            zoneValues = mapOf("TOTAL" to "10"),
                            billingPeriod = "2026-09"
                        )
                    )
                ),
                Meter(id = "m2", name = "Газ", unit = "м³", meteringPointId = "p2")
            )
        )
        val template = TransmissionTemplate(
            id = "t1",
            addressId = "a1",
            name = "УК",
            recipient = "УК",
            meteringPointIds = setOf("p1", "p2")
        )

        val prepared = SubmissionWorkflow.prepare(address, template, java.time.YearMonth.of(2026, 9))

        assertEquals(setOf("p2"), prepared.missingPointIds)
        assertEquals(setOf("p1"), prepared.items.map { it.meteringPointId }.toSet())
        assertEquals("УК", SubmissionWorkflow.createSubmission(address, template, java.time.YearMonth.of(2026, 9), prepared).recipient)
    }
}
