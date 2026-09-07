package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class ReminderPolicyTest {
    @Test fun transferWindowClampsDaysAtMonthEnd() {
        assertEquals(28, ReminderPolicy.clampDay(YearMonth.of(2027, 2), 31))
        val window = TransferWindow(startDay = 28, endDay = 31)
        assertEquals(TransferStatus.DUE, ReminderPolicy.transferStatus(LocalDate.of(2027, 2, 28), window))
        assertEquals(TransferStatus.PASSED, ReminderPolicy.transferStatus(LocalDate.of(2027, 3, 31), TransferWindow(28, 30)))
    }

    @Test fun disabledWindowNeverBecomesDue() {
        assertEquals(
            TransferStatus.DISABLED,
            ReminderPolicy.transferStatus(LocalDate.of(2026, 9, 20), TransferWindow(enabled = false))
        )
    }

    @Test fun verificationDistinguishesSoonAndExpired() {
        val zone = ZoneOffset.UTC
        val now = LocalDate.of(2026, 9, 1)
        val soon = LocalDate.of(2026, 9, 20).atStartOfDay(zone).toInstant().toEpochMilli()
        val expired = LocalDate.of(2026, 8, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(VerificationStatus.SOON, ReminderPolicy.verificationStatus(soon, now, 30, zone))
        assertEquals(VerificationStatus.EXPIRED, ReminderPolicy.verificationStatus(expired, now, 30, zone))
    }
}
