package ru.egor.meters

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class TransmissionTemplate(
    val id: String = UUID.randomUUID().toString(),
    val addressId: String,
    val name: String,
    val recipient: String = "",
    val account: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val meteringPointIds: Set<String> = emptySet()
)

class TransmissionTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("submission_templates", Context.MODE_PRIVATE)

    fun list(address: Address): List<TransmissionTemplate> {
        val raw = prefs.getString(key(address.id), null)
        val parsed = raw?.let(::parse).orEmpty()
        if (parsed.isNotEmpty()) return parsed
        return listOf(defaultTemplate(address))
    }

    fun save(addressId: String, templates: List<TransmissionTemplate>) {
        val array = JSONArray()
        templates.forEach { template ->
            array.put(JSONObject().apply {
                put("id", template.id)
                put("addressId", addressId)
                put("name", template.name)
                put("recipient", template.recipient)
                put("account", template.account)
                put("prefix", template.prefix)
                put("suffix", template.suffix)
                put("points", JSONArray(template.meteringPointIds.toList()))
            })
        }
        prefs.edit().putString(key(addressId), array.toString()).apply()
    }

    fun defaultTemplate(address: Address): TransmissionTemplate = TransmissionTemplate(
        addressId = address.id,
        name = address.recipient.ifBlank { "Все показания" },
        recipient = address.recipient,
        account = address.account,
        meteringPointIds = address.meters.filter { it.status != "closed" }.map { it.meteringPointId ?: it.id }.toSet()
    )

    private fun parse(raw: String): List<TransmissionTemplate> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val points = item.optJSONArray("points") ?: JSONArray()
                add(TransmissionTemplate(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    addressId = item.optString("addressId"),
                    name = item.optString("name", "Получатель"),
                    recipient = item.optString("recipient"),
                    account = item.optString("account"),
                    prefix = item.optString("prefix"),
                    suffix = item.optString("suffix"),
                    meteringPointIds = buildSet { for (j in 0 until points.length()) add(points.getString(j)) }
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun key(addressId: String) = "address:$addressId"
}

data class PreparedSubmission(
    val text: String,
    val items: List<SubmissionItem>,
    val missingPointIds: Set<String>
)

enum class SubmissionStatus { NONE, PARTIAL, COMPLETE }

data class TemplateSubmissionStatus(
    val status: SubmissionStatus,
    val submittedPointIds: Set<String>,
    val requiredPointIds: Set<String>
)

object SubmissionWorkflow {
    private const val TEMPLATE_ID_SEPARATOR = ":submission:"

    fun prepare(address: Address, template: TransmissionTemplate, period: YearMonth): PreparedSubmission {
        require(template.addressId == address.id) { "Шаблон относится к другому адресу" }
        val selected = selectedPointIds(address, template)
        val items = mutableListOf<SubmissionItem>()
        val lines = mutableListOf<String>()
        val missing = linkedSetOf<String>()

        address.meters.filter { it.status != "closed" }.forEach { meter ->
            val pointId = meter.meteringPointId ?: meter.id
            if (pointId !in selected) return@forEach
            val reading = meter.readings
                .filter { readingPeriod(it) == period }
                .maxByOrNull { it.timestamp }
            if (reading == null) {
                missing += pointId
                return@forEach
            }
            val sourceValues = reading.zoneValues.takeIf { it.isNotEmpty() }
                ?: mapOf("TOTAL" to MeterHistory.readingText(reading))
            val values = ReadingValidator.normalizeForMeter(sourceValues, meter)
            if (values == null) {
                missing += pointId
                return@forEach
            }
            val zones = MeterZones.normalize(meter.tariffZones)
            zones.forEach { zone ->
                items += SubmissionItem(pointId, meter.id, zone, values.getValue(zone))
            }
            val rendered = zones.joinToString(" · ") { zone ->
                val value = values.getValue(zone)
                if (zone == "TOTAL") value else "$zone $value"
            }
            lines += "${meter.name}: $rendered ${meter.unit}".trim()
        }

        val text = buildList {
            template.prefix.trim().takeIf { it.isNotBlank() }?.let(::add)
            add(address.name)
            template.account.trim().takeIf { it.isNotBlank() }?.let { add("Лицевой счёт: $it") }
            addAll(lines)
            template.suffix.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
        return PreparedSubmission(text, items, missing)
    }

    fun createSubmission(
        address: Address,
        template: TransmissionTemplate,
        period: YearMonth,
        prepared: PreparedSubmission = prepare(address, template, period),
        submittedAt: Long = System.currentTimeMillis()
    ): Submission {
        require(prepared.items.isNotEmpty()) { "Нет показаний для передачи" }
        require(!template.id.contains(TEMPLATE_ID_SEPARATOR)) { "Некорректный идентификатор шаблона" }
        return Submission(
            id = templateSubmissionId(template.id),
            addressId = address.id,
            billingPeriod = period.toString(),
            submittedAt = submittedAt,
            recipient = template.recipient,
            snapshotText = prepared.text,
            items = prepared.items
        )
    }

    fun statusForAddress(repo: MeterRepository, address: Address, period: YearMonth): SubmissionStatus {
        val activePointIds = address.meters.filter { it.status != "closed" }.map { it.meteringPointId ?: it.id }.toSet()
        return statusForPointSet(repo, address, period, activePointIds, null).status
    }

    fun statusForTemplate(repo: MeterRepository, address: Address, template: TransmissionTemplate, period: YearMonth): TemplateSubmissionStatus =
        statusForPointSet(repo, address, period, selectedPointIds(address, template), template.id)

    private fun statusForPointSet(
        repo: MeterRepository,
        address: Address,
        period: YearMonth,
        requiredPointIds: Set<String>,
        templateId: String?
    ): TemplateSubmissionStatus {
        if (requiredPointIds.isEmpty()) return TemplateSubmissionStatus(SubmissionStatus.NONE, emptySet(), emptySet())

        val requiredKeys = address.meters
            .filter { it.status != "closed" && (it.meteringPointId ?: it.id) in requiredPointIds }
            .flatMap { meter ->
                val pointId = meter.meteringPointId ?: meter.id
                MeterZones.normalize(meter.tariffZones).map { zone -> pointId to zone }
            }
            .toSet()
        val submittedKeys = repo.submissions(address.id)
            .filter { it.billingPeriod == period.toString() && (templateId == null || submissionBelongsToTemplate(it.id, templateId)) }
            .flatMap { it.items }
            .map { it.meteringPointId to it.zone.uppercase() }
            .toSet()
            .intersect(requiredKeys)
        val submittedPointIds = submittedKeys.map { it.first }.toSet()
        val status = when {
            requiredKeys.isNotEmpty() && submittedKeys.containsAll(requiredKeys) -> SubmissionStatus.COMPLETE
            submittedKeys.isNotEmpty() -> SubmissionStatus.PARTIAL
            else -> SubmissionStatus.NONE
        }
        return TemplateSubmissionStatus(status, submittedPointIds, requiredPointIds)
    }

    internal fun submissionBelongsToTemplate(submissionId: String, templateId: String): Boolean =
        submissionId.startsWith("$templateId$TEMPLATE_ID_SEPARATOR")

    private fun templateSubmissionId(templateId: String): String =
        "$templateId$TEMPLATE_ID_SEPARATOR${UUID.randomUUID()}"

    private fun selectedPointIds(address: Address, template: TransmissionTemplate): Set<String> =
        template.meteringPointIds.ifEmpty {
            address.meters.filter { it.status != "closed" }.map { it.meteringPointId ?: it.id }.toSet()
        }

    private fun readingPeriod(reading: Reading): YearMonth? = reading.billingPeriod?.let {
        runCatching { YearMonth.parse(it) }.getOrNull()
    } ?: runCatching {
        // Legacy compatibility only. New walkthrough readings must set billingPeriod explicitly.
        YearMonth.from(Instant.ofEpochMilli(reading.timestamp).atZone(ZoneId.systemDefault()))
    }.getOrNull()
}
