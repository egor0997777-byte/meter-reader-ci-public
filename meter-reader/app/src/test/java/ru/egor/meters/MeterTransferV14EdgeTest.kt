package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MeterTransferV14EdgeTest {
    @Test
    fun rejectsUnsafeArchiveEntryBeforeRestore() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(legacyV1Manifest().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        assertTrue(runCatching { MeterTransfer.parseBackup(out.toByteArray()) }.isFailure)
    }

    @Test
    fun rejectsInvalidBillingPeriodBeforeBackupIsCreated() {
        val address = Address(
            id = "a-period",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m-period",
                    name = "Вода",
                    unit = "м³",
                    meteringPointId = "p-period",
                    readings = listOf(
                        Reading(
                            id = "r-period",
                            value = 12.0,
                            valueText = "12",
                            timestamp = 1L,
                            zoneValues = mapOf("TOTAL" to "12"),
                            billingPeriod = "2026-13"
                        )
                    )
                )
            )
        )
        assertTrue(runCatching { MeterTransfer.createBackupBytes(listOf(address)) }.isFailure)
    }

    @Test
    fun rejectsBackupThatClaimsPhotoButDoesNotContainIt() {
        val address = Address(
            id = "a-photo",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m-photo",
                    name = "Газ",
                    unit = "м³",
                    meteringPointId = "p-photo",
                    readings = listOf(
                        Reading(
                            id = "r-photo",
                            value = 1.0,
                            valueText = "1",
                            timestamp = 1L,
                            photoUri = "content://ru.egor.meters.fileprovider/photo.jpg",
                            zoneValues = mapOf("TOTAL" to "1"),
                            billingPeriod = "2026-09"
                        )
                    )
                )
            )
        )
        assertTrue(runCatching { MeterTransfer.createBackupBytes(listOf(address), photos = emptyMap()) }.isFailure)
    }

    @Test
    fun legacyV3BackupStillParsesWithoutSubmissionData() {
        val payload = MeterTransfer.parseBackup(zipWithManifest(legacyV3Manifest()))
        val meter = payload.addresses.single().meters.single()
        assertEquals("Кухня", meter.location)
        assertEquals("00042.500", meter.readings.single().valueText)
        assertTrue(payload.submissions.isEmpty())
    }

    private fun zipWithManifest(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun legacyV1Manifest() = """{"format":"moi-schetschiki-backup","version":1,"addresses":[]}"""

    private fun legacyV3Manifest() = """
        {
          "format":"moi-schetschiki-backup",
          "version":3,
          "addresses":[
            {
              "id":"a3",
              "name":"Дом",
              "meters":[
                {
                  "id":"m3",
                  "name":"Холодная вода",
                  "unit":"м³",
                  "kind":"cold_water",
                  "serial":"123",
                  "location":"Кухня",
                  "tariffZones":["TOTAL"],
                  "tariffSchedule":[],
                  "readings":[
                    {
                      "id":"r3",
                      "valueText":"00042.500",
                      "zoneValues":{"TOTAL":"00042.500"},
                      "timestamp":3,
                      "note":"",
                      "rollover":false,
                      "hasPhoto":false
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()
}
