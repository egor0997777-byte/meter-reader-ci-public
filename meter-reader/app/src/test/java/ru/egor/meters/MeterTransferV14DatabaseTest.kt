package ru.egor.meters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MeterTransferV14DatabaseTest {
    private val address = Address(
        id = "a-db",
        name = "Дом",
        meters = listOf(
            Meter(
                id = "m-db",
                name = "Вода",
                unit = "м³",
                meteringPointId = "p-db",
                readings = listOf(
                    Reading(
                        id = "r-db",
                        value = 12.0,
                        valueText = "12",
                        timestamp = 1L,
                        zoneValues = mapOf("TOTAL" to "12"),
                        billingPeriod = "2026-09"
                    )
                )
            )
        )
    )

    @Test
    fun v4BackupContainsDatabaseEntry() {
        val archive = MeterTransfer.createBackupBytes(listOf(address))
        val entries = unzip(archive)
        assertTrue("database/meter-reader.db" in entries)
        assertArrayEquals(
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII),
            entries.getValue("database/meter-reader.db").copyOfRange(0, 16)
        )
        assertTrue(MeterTransfer.parseBackup(archive).databaseBytes != null)
    }

    @Test
    fun v4BackupWithoutDatabaseEntryIsRejected() {
        val archive = MeterTransfer.createBackupBytes(listOf(address))
        val entries = unzip(archive).filterKeys { it != "database/meter-reader.db" }
        val rewritten = zip(entries)
        assertTrue(runCatching { MeterTransfer.parseBackup(rewritten) }.isFailure)
    }

    @Test
    fun v4TamperedDatabaseEntryIsRejectedByManifestChecksum() {
        val archive = MeterTransfer.createBackupBytes(listOf(address))
        val entries = unzip(archive).toMutableMap()
        entries["database/meter-reader.db"] = entries.getValue("database/meter-reader.db") + byteArrayOf(1)
        assertTrue(runCatching { MeterTransfer.parseBackup(zip(entries)) }.isFailure)
    }

    private fun unzip(input: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
