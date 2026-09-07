package ru.egor.meters

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReminderWindowBackup(
    val enabled: Boolean,
    val startDay: Int,
    val endDay: Int
)

data class BackupMetadata(
    val submissionTemplates: Map<String, String> = emptyMap(),
    val notificationsEnabled: Boolean = true,
    val reminderWindows: Map<String, ReminderWindowBackup> = emptyMap(),
    val lastVerificationByMeter: Map<String, Long> = emptyMap()
)

object BackupMetadataStore {
    private const val METADATA_VERSION = 1
    private const val TEMPLATE_PREFS = "submission_templates"
    private const val REMINDER_PREFS = "meter_reminders"

    fun capture(context: Context, addresses: List<Address>): BackupMetadata {
        val addressIds = addresses.map { it.id }.toSet()
        val meterIds = addresses.flatMap { it.meters }.map { it.id }.toSet()
        val templates = context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
        val reminders = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)

        val templateValues = addressIds.mapNotNull { addressId ->
            templates.getString("address:$addressId", null)?.let { addressId to it }
        }.toMap()
        val windows = addressIds.associateWith { addressId ->
            ReminderWindowBackup(
                enabled = reminders.getBoolean("address:$addressId:enabled", true),
                startDay = reminders.getInt("address:$addressId:start_day", 20).coerceIn(1, 31),
                endDay = reminders.getInt("address:$addressId:end_day", 25).coerceIn(1, 31)
            )
        }
        val verification = meterIds.mapNotNull { meterId ->
            val key = "meter:$meterId:last_verification"
            if (reminders.contains(key)) meterId to reminders.getLong(key, 0L) else null
        }.toMap()
        return BackupMetadata(
            submissionTemplates = templateValues,
            notificationsEnabled = reminders.getBoolean("notifications_enabled", true),
            reminderWindows = windows,
            lastVerificationByMeter = verification
        )
    }

    fun validate(metadata: BackupMetadata, addresses: List<Address>) {
        val addressIds = addresses.map { it.id }.toSet()
        val meterIds = addresses.flatMap { it.meters }.map { it.id }.toSet()
        val pointsByAddress = addresses.associate { address ->
            address.id to address.meters.map { it.meteringPointId ?: it.id }.toSet()
        }
        require(metadata.submissionTemplates.keys.all { it in addressIds }) { "Backup содержит шаблон неизвестного адреса" }
        require(metadata.reminderWindows.keys.all { it in addressIds }) { "Backup содержит напоминание неизвестного адреса" }
        require(metadata.lastVerificationByMeter.keys.all { it in meterIds }) { "Backup содержит поверку неизвестного счётчика" }
        metadata.reminderWindows.values.forEach {
            require(it.startDay in 1..31 && it.endDay in 1..31) { "Некорректное окно напоминания" }
        }
        metadata.lastVerificationByMeter.values.forEach {
            require(it >= 0L) { "Некорректная дата последней поверки" }
        }
        metadata.submissionTemplates.forEach { (addressId, raw) ->
            require(raw.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Слишком большой набор шаблонов передачи" }
            val array = runCatching { JSONArray(raw) }.getOrElse { error("Повреждены шаблоны передачи") }
            require(array.length() <= 128) { "Слишком много шаблонов передачи" }
            val templateIds = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val templateId = item.optString("id")
                require(templateId.isNotBlank() && templateId.length <= 160) { "Некорректный идентификатор шаблона передачи" }
                require(templateIds.add(templateId)) { "Повторяющийся идентификатор шаблона передачи" }
                require(item.optString("addressId") == addressId) { "Шаблон передачи относится к другому адресу" }
                require(item.optString("name").isNotBlank() && item.optString("name").length <= 160) { "Некорректное имя шаблона передачи" }
                require(item.optString("recipient").length <= 240) { "Слишком длинный получатель шаблона передачи" }
                require(item.optString("account").length <= 160) { "Слишком длинный лицевой счёт шаблона передачи" }
                require(item.optString("prefix").length <= 1000 && item.optString("suffix").length <= 1000) { "Слишком длинный текст шаблона передачи" }
                val points = item.optJSONArray("points") ?: JSONArray()
                require(points.length() <= 128) { "Слишком много точек в шаблоне передачи" }
                val seenPoints = mutableSetOf<String>()
                for (j in 0 until points.length()) {
                    val pointId = points.getString(j)
                    require(pointId in pointsByAddress[addressId].orEmpty()) { "Шаблон передачи ссылается на неизвестную точку учёта" }
                    require(seenPoints.add(pointId)) { "Шаблон передачи содержит повторяющуюся точку учёта" }
                }
            }
        }
    }

    fun apply(context: Context, metadata: BackupMetadata, addresses: List<Address>) {
        validate(metadata, addresses)
        val templatePrefs = context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
        val reminderPrefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)

        val templateEditor = templatePrefs.edit().clear()
        metadata.submissionTemplates.forEach { (addressId, raw) ->
            templateEditor.putString("address:$addressId", raw)
        }
        check(templateEditor.commit()) { "Не удалось восстановить шаблоны передачи" }

        val reminderEditor = reminderPrefs.edit().clear()
            .putBoolean("notifications_enabled", metadata.notificationsEnabled)
        metadata.reminderWindows.forEach { (addressId, window) ->
            reminderEditor
                .putBoolean("address:$addressId:enabled", window.enabled)
                .putInt("address:$addressId:start_day", window.startDay)
                .putInt("address:$addressId:end_day", window.endDay)
        }
        metadata.lastVerificationByMeter.forEach { (meterId, millis) ->
            reminderEditor.putLong("meter:$meterId:last_verification", millis)
        }
        check(reminderEditor.commit()) { "Не удалось восстановить настройки напоминаний" }

        // Security credentials/settings and an in-progress walkthrough are intentionally device/session specific.
        // They are not transferred to another phone. A successful restore starts with no stale walkthrough session.
        context.getSharedPreferences("v13_walkthrough", Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun toJson(metadata: BackupMetadata): JSONObject = JSONObject().apply {
        put("version", METADATA_VERSION)
        put("notificationsEnabled", metadata.notificationsEnabled)
        put("submissionTemplates", JSONObject().apply {
            metadata.submissionTemplates.toSortedMap().forEach { (addressId, raw) -> put(addressId, raw) }
        })
        put("reminderWindows", JSONArray().apply {
            metadata.reminderWindows.toSortedMap().forEach { (addressId, window) ->
                put(JSONObject().apply {
                    put("addressId", addressId)
                    put("enabled", window.enabled)
                    put("startDay", window.startDay)
                    put("endDay", window.endDay)
                })
            }
        })
        put("lastVerification", JSONObject().apply {
            metadata.lastVerificationByMeter.toSortedMap().forEach { (meterId, millis) -> put(meterId, millis) }
        })
    }

    fun fromJson(source: JSONObject?): BackupMetadata {
        if (source == null) return BackupMetadata()
        require(source.optInt("version", 0) == METADATA_VERSION) { "Версия metadata резервной копии не поддерживается" }

        val templatesObject = source.optJSONObject("submissionTemplates") ?: JSONObject()
        val templates = buildMap {
            templatesObject.keys().forEach { addressId -> put(addressId, templatesObject.getString(addressId)) }
        }
        val windowsArray = source.optJSONArray("reminderWindows") ?: JSONArray()
        val windows = buildMap {
            for (i in 0 until windowsArray.length()) {
                val item = windowsArray.getJSONObject(i)
                val addressId = item.getString("addressId")
                require(addressId !in this) { "Повторяющееся окно напоминания" }
                put(addressId, ReminderWindowBackup(item.optBoolean("enabled", true), item.getInt("startDay"), item.getInt("endDay")))
            }
        }
        val verificationObject = source.optJSONObject("lastVerification") ?: JSONObject()
        val verification = buildMap {
            verificationObject.keys().forEach { meterId -> put(meterId, verificationObject.getLong(meterId)) }
        }
        return BackupMetadata(
            submissionTemplates = templates,
            notificationsEnabled = source.optBoolean("notificationsEnabled", true),
            reminderWindows = windows,
            lastVerificationByMeter = verification
        )
    }
}
