package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AuditRegressionTest {
    @Test
    fun legacyV3ReplacementChainRestoresOneLogicalPoint() {
        val json = """
            {
              "format":"moi-schetschiki-backup",
              "version":3,
              "addresses":[{
                "id":"a1","name":"Дом","meters":[
                  {"id":"old","name":"ХВС","unit":"м³","kind":"cold_water","status":"closed","readings":[]},
                  {"id":"new","name":"ХВС","unit":"м³","kind":"cold_water","previousMeterId":"old","status":"active","readings":[]}
                ]
              }]
            }
        """.trimIndent()

        val meters = MeterTransfer.parseBackup(zipLegacy(json)).addresses.single().meters
        assertEquals("old", meters.first { it.id == "old" }.meteringPointId)
        assertEquals("old", meters.first { it.id == "new" }.meteringPointId)
    }

    @Test
    fun legacyCrossAddressReplacementIsRejectedInsteadOfInventingPoint() {
        val json = """
            {
              "format":"moi-schetschiki-backup",
              "version":3,
              "addresses":[
                {"id":"a1","name":"A","meters":[{"id":"old","name":"ХВС","unit":"м³","kind":"cold_water","readings":[]}]},
                {"id":"a2","name":"B","meters":[{"id":"new","name":"ХВС","unit":"м³","kind":"cold_water","previousMeterId":"old","readings":[]}]}
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { MeterTransfer.parseBackup(zipLegacy(json)) }.isFailure)
    }

    @Test
    fun missingT2T3MakesWholePointMissing() {
        val period = YearMonth.of(2026, 9)
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m1",
                    meteringPointId = "p1",
                    name = "Электричество",
                    unit = "кВт·ч",
                    tariffZones = listOf("T1", "T2", "T3"),
                    readings = listOf(
                        Reading(
                            id = "r1",
                            value = 100.0,
                            valueText = "100",
                            zoneValues = mapOf("T1" to "100"),
                            billingPeriod = period.toString()
                        )
                    )
                )
            )
        )
        val template = TransmissionTemplate(addressId = address.id, name = "Энергосбыт", meteringPointIds = setOf("p1"))

        val prepared = SubmissionWorkflow.prepare(address, template, period)
        assertTrue("p1" in prepared.missingPointIds)
        assertTrue(prepared.items.isEmpty())
    }

    @Test
    fun completeT1T2T3ProducesThreeSubmissionItems() {
        val period = YearMonth.of(2026, 9)
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m1",
                    meteringPointId = "p1",
                    name = "Электричество",
                    unit = "кВт·ч",
                    tariffZones = listOf("T1", "T2", "T3"),
                    readings = listOf(
                        Reading(
                            id = "r1",
                            value = 100.0,
                            valueText = "100",
                            zoneValues = linkedMapOf("T1" to "100", "T2" to "50", "T3" to "10"),
                            billingPeriod = period.toString()
                        )
                    )
                )
            )
        )
        val template = TransmissionTemplate(addressId = address.id, name = "Энергосбыт", meteringPointIds = setOf("p1"))

        val prepared = SubmissionWorkflow.prepare(address, template, period)
        assertTrue(prepared.missingPointIds.isEmpty())
        assertEquals(listOf("T1", "T2", "T3"), prepared.items.map { it.zone })
    }

    private fun zipLegacy(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}