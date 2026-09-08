package ru.egor.meters

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.YearMonth

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

        val next = addresses.firstOrNull { address ->
            MonthlyWalkPlanner.progress(address, now).remainingCount > 0
        } ?: return null
        return TakeReadingsTarget(next.id, now, false)
    }

    fun remainingForPeriod(address: Address, period: YearMonth): Int =
        MonthlyWalkPlanner.progress(address, period).remainingCount
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
            val address = addresses.first { it.id == target.addressId }
            check(
                prefs.edit()
                    .putString("address", target.addressId)
                    .putString("period", target.period.toString())
                    .putStringSet("done", MonthlyWalkPlanner.completedMeterIds(address, target.period))
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
