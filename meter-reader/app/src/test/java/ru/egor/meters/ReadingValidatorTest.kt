package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReadingValidatorTest {
    private fun electricity(zones: List<String> = MeterZones.SINGLE) = Meter(
        id = "m1",
        name = "Электричество",
        unit = "кВт·ч",
        kind = "electricity",
        integerDigits = 6,
        fractionDigits = 1,
        tariffZones = zones
    )

    @Test
    fun keepsLeadingZerosAsCanonicalText() {
        val result = ReadingValidator.normalizeForMeter(mapOf("TOTAL" to "000123.4"), electricity())
        assertEquals("000123.4", result?.get("TOTAL"))
    }

    @Test
    fun rejectsTooManyIntegerDigits() {
        assertNull(ReadingValidator.normalizeForMeter(mapOf("TOTAL" to "1234567.0"), electricity()))
    }

    @Test
    fun rejectsTooManyFractionDigits() {
        assertNull(ReadingValidator.normalizeForMeter(mapOf("TOTAL" to "123.45"), electricity()))
    }

    @Test
    fun requiresEveryConfiguredTariffZone() {
        val meter = electricity(MeterZones.THREE_TARIFF)
        assertNull(ReadingValidator.normalizeForMeter(mapOf("T1" to "10.0", "T2" to "20.0"), meter))
        assertNotNull(ReadingValidator.normalizeForMeter(mapOf("T1" to "10.0", "T2" to "20.0", "T3" to "30.0"), meter))
    }

    @Test
    fun rejectsUnexpectedTariffZoneShape() {
        val digits = ReadingValidator.Digits(6, 1)
        assertNull(ReadingValidator.normalizeValues(mapOf("T1" to "1.0", "T3" to "3.0"), digits))
        assertNull(ReadingValidator.normalizeValues(mapOf("T1" to "1.0", "T2" to "2.0", "T3" to "3.0", "T4" to "4.0"), digits))
    }

    @Test
    fun zeroFractionDigitsRejectDecimalSeparator() {
        val digits = ReadingValidator.Digits(6, 0)
        assertEquals("000123", ReadingValidator.normalizeValue("000123", digits))
        assertNull(ReadingValidator.normalizeValue("000123.0", digits))
    }
}
