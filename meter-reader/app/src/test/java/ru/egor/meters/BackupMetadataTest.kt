package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataTest {
    @Test
    fun metadataJsonRoundTripKeepsTemplatesReminderWindowsAndVerification() {
        val source = BackupMetadata(
            submissionTemplates = mapOf("a1" to "[{\"id\":\"t1\"}]"),
            notificationsEnabled = false,
            reminderWindows = mapOf("a1" to ReminderWindowBackup(enabled = true, startDay = 19, endDay = 24)),
            lastVerificationByMeter = mapOf("m1" to 123456789L)
        )

        val restored = BackupMetadataStore.fromJson(BackupMetadataStore.toJson(source))

        assertEquals(source, restored)
        assertFalse(restored.notificationsEnabled)
    }

    @Test
    fun emptyMetadataIsBackwardCompatible() {
        assertEquals(BackupMetadata(), BackupMetadataStore.fromJson(null))
    }

    @Test
    fun validTemplateMetadataMustBelongToBackedUpAddressAndPoint() {
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(Meter(id = "m1", meteringPointId = "p1", name = "Вода", unit = "м³"))
        )
        val metadata = BackupMetadata(
            submissionTemplates = mapOf(
                "a1" to """[{"id":"t1","addressId":"a1","name":"УК","recipient":"УК","account":"123","prefix":"","suffix":"","points":["p1"]}]"""
            ),
            reminderWindows = mapOf("a1" to ReminderWindowBackup(true, 20, 25)),
            lastVerificationByMeter = mapOf("m1" to 1L)
        )

        BackupMetadataStore.validate(metadata, listOf(address))
        assertTrue(true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemplatePointOutsideBackedUpAddress() {
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(Meter(id = "m1", meteringPointId = "p1", name = "Вода", unit = "м³"))
        )
        val metadata = BackupMetadata(
            submissionTemplates = mapOf(
                "a1" to """[{"id":"t1","addressId":"a1","name":"УК","points":["foreign-point"]}]"""
            )
        )

        BackupMetadataStore.validate(metadata, listOf(address))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateTemplateIdsInsideAddress() {
        val address = Address(id = "a1", name = "Дом")
        val metadata = BackupMetadata(
            submissionTemplates = mapOf(
                "a1" to """[{"id":"t1","addressId":"a1","name":"УК","points":[]},{"id":"t1","addressId":"a1","name":"ТСЖ","points":[]}]"""
            )
        )

        BackupMetadataStore.validate(metadata, listOf(address))
    }
}
