package ru.egor.meters

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class TakeReadingsTarget(
    val addressId: String,
    val period: YearMonth,
    val resumeExisting: Boolean
)

object TakeReadingsShortcutRouting {
    fun resolve(
        addresses: List<Address>,
        sessionAddressId: String?,
        sessionPeriod: YearMonth?,
        now: YearMonth = YearMonth.now()
    ): TakeReadingsTarget? {
        val resumable = addresses.firstOrNull { address ->
            address.id == sessionAddressId && address.meters.any { it.status != "closed" }
        }
        if (resumable != null && sessionPeriod != null) {
            return TakeReadingsTarget(resumable.id, sessionPeriod, true)
        }

        val activeAddresses = addresses.filter { address -> address.meters.any { it.status != "closed" } }
        val next = activeAddresses.firstOrNull { remainingForPeriod(it, now) > 0 }
            ?: activeAddresses.firstOrNull()
            ?: return null
        return TakeReadingsTarget(next.id, now, false)
    }

    fun remainingForPeriod(address: Address, period: YearMonth): Int =
        address.meters.count { meter ->
            meter.status != "closed" && meter.readings.none { readingPeriod(it) == period }
        }

    private fun readingPeriod(reading: Reading): YearMonth? {
        reading.billingPeriod?.let { stored ->
            runCatching { YearMonth.parse(stored) }.getOrNull()?.let { return it }
        }
        return runCatching {
            YearMonth.from(Instant.ofEpochMilli(reading.timestamp).atZone(ZoneId.systemDefault()))
        }.getOrNull()
    }
}

class TakeReadingsShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMonthlyFlow()
        finish()
    }

    private fun openMonthlyFlow() {
        val repository = MeterRepository(this)
        val addresses = repository.load()
        val prefs = getSharedPreferences("v13_walkthrough", Context.MODE_PRIVATE)
        val sessionAddressId = prefs.getString("address", null)
        val sessionPeriod = prefs.getString("period", null)
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val target = TakeReadingsShortcutRouting.resolve(addresses, sessionAddressId, sessionPeriod)

        if (target != null && !target.resumeExisting) {
            check(
                prefs.edit()
                    .putString("address", target.addressId)
                    .putString("period", target.period.toString())
                    .putStringSet("done", emptySet())
                    .putStringSet("skipped", emptySet())
                    .commit()
            ) { "Не удалось подготовить быстрый запуск снятия показаний" }
        }

        startActivity(
            Intent(this, V13MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}