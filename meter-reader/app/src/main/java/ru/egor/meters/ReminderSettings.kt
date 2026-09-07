package ru.egor.meters

import android.content.Context
import java.time.YearMonth

data class AddressReminderSettings(
    val enabled: Boolean = true,
    val startDay: Int = 20,
    val endDay: Int = 25
)

class ReminderSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("meter_reminders", Context.MODE_PRIVATE)

    fun notificationsEnabled(): Boolean = prefs.getBoolean("notifications_enabled", true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun getAddress(addressId: String): AddressReminderSettings = AddressReminderSettings(
        enabled = prefs.getBoolean(key(addressId, "enabled"), true),
        startDay = prefs.getInt(key(addressId, "start_day"), 20).coerceIn(1, 31),
        endDay = prefs.getInt(key(addressId, "end_day"), 25).coerceIn(1, 31)
    )

    fun saveAddress(addressId: String, settings: AddressReminderSettings) {
        prefs.edit()
            .putBoolean(key(addressId, "enabled"), settings.enabled)
            .putInt(key(addressId, "start_day"), settings.startDay.coerceIn(1, 31))
            .putInt(key(addressId, "end_day"), settings.endDay.coerceIn(1, 31))
            .remove(key(addressId, "last_transferred_month"))
            .apply()
    }

    fun isTransferred(addressId: String, month: YearMonth): Boolean {
        val repo = MeterRepository(appContext)
        val address = repo.load().firstOrNull { it.id == addressId } ?: return false
        return SubmissionWorkflow.statusForAddress(repo, address, month) == SubmissionStatus.COMPLETE
    }

    fun getLastVerification(meterId: String): Long? =
        prefs.getLong(meterKey(meterId, "last_verification"), Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    fun setLastVerification(meterId: String, millis: Long?) {
        val editor = prefs.edit()
        if (millis == null) editor.remove(meterKey(meterId, "last_verification"))
        else editor.putLong(meterKey(meterId, "last_verification"), millis)
        editor.apply()
    }

    private fun key(addressId: String, suffix: String) = "address:$addressId:$suffix"
    private fun meterKey(meterId: String, suffix: String) = "meter:$meterId:$suffix"
}
