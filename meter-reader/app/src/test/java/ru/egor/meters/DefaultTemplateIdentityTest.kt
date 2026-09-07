package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultTemplateIdentityTest {
    @Test
    fun defaultTemplateIdIsStablePerAddress() {
        val first = TransmissionTemplateStore.defaultTemplateId("address-1")
        val second = TransmissionTemplateStore.defaultTemplateId("address-1")
        val other = TransmissionTemplateStore.defaultTemplateId("address-2")

        assertEquals(first, second)
        assertFalse(first == other)
        assertFalse(first.contains(":submission:"))
    }
}
