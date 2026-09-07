package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyBackupReplacementCoverageTest {
    @Test
    fun v1SingleMeterGetsStableSelfPointWithoutInventingSubmission() {
        val payload = MeterTransfer.parseBackup(zip("""
            {
              "format":"moi-schetschiki-backup",
              "version":1,
              "addresses":[{
                "id":"a1","name":"Дом","meters":[{
                  "id":"m1","name":"Вода","unit":"м³","readings":[]
                }]
              }]
            }
        """.trimIndent()))

        assertEquals("m1", payload.addresses.single().meters.single().meteringPointId)
        assertTrue(payload.submissions.isEmpty())
        assertTrue(!payload.metadata.included)
    }

    @Test
    fun v2ThreeDeviceReplacementChainResolvesToOriginalPoint() {
        val payload = MeterTransfer.parseBackup(zip("""
            {
              "format":"moi-schetschiki-backup",
              "version":2,
              "addresses":[{
                "id":"a1","name":"Дом","meters":[
                  {"id":"m1","name":"Вода","unit":"м³","status":"closed","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]},
                  {"id":"m2","name":"Вода","unit":"м³","previousMeterId":"m1","status":"closed","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]},
                  {"id":"m3","name":"Вода","unit":"м³","previousMeterId":"m2","status":"active","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]}
                ]
              }]
            }
        """.trimIndent()))

        val meters = payload.addresses.single().meters.associateBy { it.id }
        assertEquals("m1", meters.getValue("m1").meteringPointId)
        assertEquals("m1", meters.getValue("m2").meteringPointId)
        assertEquals("m1", meters.getValue("m3").meteringPointId)
    }

    @Test
    fun v3CyclicReplacementChainIsRejected() {
        val archive = zip("""
            {
              "format":"moi-schetschiki-backup",
              "version":3,
              "addresses":[{
                "id":"a1","name":"Дом","meters":[
                  {"id":"m1","name":"Вода","unit":"м³","previousMeterId":"m2","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]},
                  {"id":"m2","name":"Вода","unit":"м³","previousMeterId":"m1","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]}
                ]
              }]
            }
        """.trimIndent())

        assertTrue(runCatching { MeterTransfer.parseBackup(archive) }.isFailure)
    }

    @Test
    fun v3MissingPreviousDeviceIsRejectedInsteadOfCreatingNewPoint() {
        val archive = zip("""
            {
              "format":"moi-schetschiki-backup",
              "version":3,
              "addresses":[{
                "id":"a1","name":"Дом","meters":[
                  {"id":"m2","name":"Вода","unit":"м³","previousMeterId":"missing","tariffZones":["TOTAL"],"tariffSchedule":[],"readings":[]}
                ]
              }]
            }
        """.trimIndent())

        assertTrue(runCatching { MeterTransfer.parseBackup(archive) }.isFailure)
    }

    private fun zip(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            archive.putNextEntry(ZipEntry("manifest.json"))
            archive.write(manifest.toByteArray())
            archive.closeEntry()
        }
        return out.toByteArray()
    }
}
