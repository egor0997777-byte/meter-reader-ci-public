package ru.egor.meters

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class TransferWindow(
    val startDay: Int = 20,
    val endDay: Int = 25,
    val enabled: Boolean = true
)

enum class TransferStatus { DISABLED, UPCOMING, DUE, PASSED }
enum class VerificationStatus { UNKNOWN, OK, SOON, EXPIRED }

object ReminderPolicy {
    fun clampDay(yearMonth: YearMonth, requestedDay: Int): Int =
        requestedDay.coerceIn(1, yearMonth.lengthOfMonth())

    fun transferStatus(date: LocalDate, window: TransferWindow): TransferStatus {
        if (!window.enabled) return TransferStatus.DISABLED
        val month = YearMonth.from(date)
        val start = clampDay(month, window.startDay)
        val end = clampDay(month, window.endDay)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        return when {
            date.dayOfMonth < from -> TransferStatus.UPCOMING
            date.dayOfMonth <= to -> TransferStatus.DUE
            else -> TransferStatus.PASSED
        }
    }

    fun verificationStatus(
        verificationUntilMillis: Long?,
        now: LocalDate,
        soonDays: Long = 30,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): VerificationStatus {
        val millis = verificationUntilMillis ?: return VerificationStatus.UNKNOWN
        val until = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return when {
            until.isBefore(now) -> VerificationStatus.EXPIRED
            !until.isAfter(now.plusDays(soonDays)) -> VerificationStatus.SOON
            else -> VerificationStatus.OK
        }
    }
}
