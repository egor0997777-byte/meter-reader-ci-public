package ru.egor.meters

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class MeterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLockCoordinator.install(this)
        ReminderScheduler.ensureScheduled(this)
    }
}

object ReminderScheduler {
    private const val UNIQUE_WORK = "meter-reminder-daily"

    fun ensureScheduled(application: Application) {
        val now = ZonedDateTime.now()
        var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}