package ru.egor.meters

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.YearMonth

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val settingsStore = ReminderSettingsStore(applicationContext)
        if (!settingsStore.notificationsEnabled()) return Result.success()
        ensureChannel()
        if (!canPostNotifications()) return Result.success()

        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val repo = MeterRepository(applicationContext)
        val addresses = repo.load()

        addresses.forEach { address ->
            val settings = settingsStore.getAddress(address.id)
            val transferStatus = ReminderPolicy.transferStatus(
                today,
                TransferWindow(settings.startDay, settings.endDay, settings.enabled)
            )
            val submissionStatus = SubmissionWorkflow.statusForAddress(repo, address, month)
            if (transferStatus == TransferStatus.DUE && submissionStatus != SubmissionStatus.COMPLETE) {
                post(
                    id = stableId("transfer:${address.id}"),
                    title = if (submissionStatus == SubmissionStatus.PARTIAL) "Передача показаний не завершена" else "Пора передать показания",
                    text = "${address.name}: окно ${settings.startDay}–${settings.endDay} числа."
                )
            }

            address.meters
                .asSequence()
                .filter { it.status != "closed" }
                .forEach { meter ->
                    when (ReminderPolicy.verificationStatus(meter.verificationUntil, today)) {
                        VerificationStatus.SOON -> post(
                            id = stableId("verification-soon:${meter.id}"),
                            title = "Скоро поверка счётчика",
                            text = "${meter.name}${meter.serial.takeIf { it.isNotBlank() }?.let { " № $it" }.orEmpty()}: срок поверки подходит."
                        )
                        VerificationStatus.EXPIRED -> post(
                            id = stableId("verification-expired:${meter.id}"),
                            title = "Срок поверки истёк",
                            text = "${meter.name}${meter.serial.takeIf { it.isNotBlank() }?.let { " № $it" }.orEmpty()}: проверьте дату поверки."
                        )
                        else -> Unit
                    }
                }
        }
        Result.success()
    }.getOrElse { Result.retry() }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Показания и поверка",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Локальные напоминания о передаче показаний и сроках поверки"
            }
        )
    }

    private fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    private fun stableId(value: String): Int = value.hashCode() and 0x7fffffff

    companion object {
        private const val CHANNEL_ID = "meter_reminders"
    }
}
