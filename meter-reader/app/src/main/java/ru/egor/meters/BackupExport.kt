package ru.egor.meters

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPayload(
    val addresses: List<Address>,
    val photos: Map<String, ByteArray>,
    val submissions: List<Submission> = emptyList(),
    val databaseBytes: ByteArray? = null,
    val metadata: BackupMetadata = BackupMetadata()
)

data class PreparedRestore(
    val addresses: List<Address>,
    val rootDir: File?
)

object MeterTransfer {
    const val FORMAT = "moi-schetschiki-backup"
    const val VERSION = 4
    private const val MAX_PHOTO_BYTES = 25 * 1024 * 1024
    private const val MAX_DATABASE_BYTES = 128 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 4096
    private const val LOCAL_BACKUP_LIMIT = 3
    private const val DATABASE_ENTRY = "database/meter-reader.db"

    fun createBackup(
        context: Context,
        addresses: List<Address>,
        submissions: List<Submission> = emptyList()
    ): ByteArray {
        val photoBytes = linkedMapOf<String, ByteArray>()
        addresses.flatMap { it.meters }.flatMap { it.readings }.forEach { reading ->
            val uri = reading.photoUri ?: return@forEach
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytesLimited(MAX_PHOTO_BYTES) }
                ?: error("Не удалось прочитать фото для показания ${reading.id}")
            require(bytes.isNotEmpty()) { "Фото для показания ${reading.id} пустое" }
            photoBytes[reading.id] = bytes
        }
        val databaseBytes = createDatabaseSnapshot(context, addresses, submissions)
        val metadata = BackupMetadataStore.capture(context, addresses)
        return createBackupBytes(addresses, submissions, photoBytes, databaseBytes, metadata)
    }

    internal fun createBackupBytes(
        addresses: List<Address>,
        submissions: List<Submission> = emptyList(),
        photos: Map<String, ByteArray> = emptyMap(),
        databaseBytes: ByteArray = sqliteHeaderStub(),
        metadata: BackupMetadata = BackupMetadata()
    ): ByteArray {
        validate(addresses, submissions)
        BackupMetadataStore.validate(metadata, addresses)
        val expectedPhotoIds = addresses.flatMap { it.meters }.flatMap { it.readings }
            .filter { it.photoUri != null }.map { it.id }.toSet()
        require(expectedPhotoIds == photos.keys) { "Набор фото не соответствует данным резервной копии" }
        require(isSQLiteDatabase(databaseBytes)) { "Снимок базы данных повреждён" }
        require(databaseBytes.size <= MAX_DATABASE_BYTES) { "Снимок базы данных слишком большой" }

        val entries = linkedMapOf<String, ByteArray>()
        entries["data.json"] = toJson(addresses, submissions, metadata).toString(2).toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_JSON_BYTES) { "Описание резервной копии слишком большое" }
        }
        entries[DATABASE_ENTRY] = databaseBytes
        photos.forEach { (readingId, bytes) ->
            require(bytes.isNotEmpty()) { "Пустое фото в резервной копии" }
            require(bytes.size <= MAX_PHOTO_BYTES) { "Слишком большое фото в резервной копии" }
            entries["photos/$readingId.jpg"] = bytes
        }
        require(entries.size <= MAX_ENTRY_COUNT) { "Слишком много файлов в резервной копии" }
        val totalBytes = entries.values.sumOf { it.size.toLong() }
        require(totalBytes <= MAX_ARCHIVE_BYTES) { "Резервная копия слишком большая" }

        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAt", System.currentTimeMillis())
            put("entries", JSONArray().apply {
                entries.forEach { (path, bytes) ->
                    put(JSONObject().apply {
                        put("path", path)
                        put("size", bytes.size)
                        put("sha256", sha256(bytes))
                    })
                }
            })
        }
        val manifestBytes = manifest.toString(2).toByteArray(StandardCharsets.UTF_8)
        require(manifestBytes.size <= MAX_JSON_BYTES) { "Manifest резервной копии слишком большой" }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray().also {
            require(it.size <= MAX_ARCHIVE_BYTES) { "Резервная копия слишком большая" }
        }
    }

    fun parseBackup(bytes: ByteArray): BackupPayload {
        require(bytes.isNotEmpty()) { "Пустой файл резервной копии" }
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Резервная копия слишком большая" }

        val entries = linkedMapOf<String, ByteArray>()
        var extractedBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(isSafeEntryName(name)) { "Некорректный путь в архиве" }
                require(name !in entries) { "Повторяющийся файл в архиве" }
                require(entries.size < MAX_ENTRY_COUNT) { "Слишком много файлов в резервной копии" }
                val limit = when {
                    name == "manifest.json" || name == "data.json" -> MAX_JSON_BYTES
                    name == DATABASE_ENTRY -> MAX_DATABASE_BYTES
                    name.startsWith("photos/") -> MAX_PHOTO_BYTES
                    else -> MAX_JSON_BYTES
                }
                val data = zip.readBytesLimited(limit)
                extractedBytes += data.size
                require(extractedBytes <= MAX_ARCHIVE_BYTES) { "Распакованные данные резервной копии слишком большие" }
                entries[name] = data
                zip.closeEntry()
            }
        }

        val manifestBytes = entries.remove("manifest.json")
            ?: error("В архиве нет manifest.json")
        val manifest = JSONObject(manifestBytes.toString(StandardCharsets.UTF_8))
        require(manifest.optString("format") == FORMAT) { "Это не резервная копия «Мои счётчики»" }
        val version = manifest.optInt("version")
        require(version in 1..VERSION) { "Версия резервной копии не поддерживается" }

        val root: JSONObject
        val photos: Map<String, ByteArray>
        val databaseBytes: ByteArray?
        if (version >= 4) {
            val declared = manifest.getJSONArray("entries")
            require(declared.length() <= MAX_ENTRY_COUNT) { "Слишком много файлов в manifest" }
            val expected = linkedMapOf<String, Pair<Long, String>>()
            for (i in 0 until declared.length()) {
                val item = declared.getJSONObject(i)
                val path = item.getString("path")
                require(isSafeEntryName(path) && path != "manifest.json") { "Некорректный путь в manifest" }
                require(path !in expected) { "Повторяющийся путь в manifest" }
                expected[path] = item.getLong("size") to item.getString("sha256").lowercase(Locale.US)
            }
            require(expected.keys == entries.keys) { "Состав архива не совпадает с manifest" }
            entries.forEach { (path, data) ->
                val (declaredSize, declaredHash) = expected.getValue(path)
                require(declaredSize == data.size.toLong()) { "Размер файла $path не совпадает с manifest" }
                require(declaredHash == sha256(data)) { "Контрольная сумма файла $path не совпадает" }
            }
            val dataBytes = entries["data.json"] ?: error("В архиве нет data.json")
            databaseBytes = entries[DATABASE_ENTRY] ?: error("В архиве нет снимка Room DB")
            require(databaseBytes.size <= MAX_DATABASE_BYTES && isSQLiteDatabase(databaseBytes)) { "Снимок Room DB повреждён" }
            root = JSONObject(dataBytes.toString(StandardCharsets.UTF_8))
            require(root.optString("format") == FORMAT && root.optInt("version") == version) {
                "Данные резервной копии не соответствуют manifest"
            }
            photos = entries.filterKeys { it.startsWith("photos/") && it.endsWith(".jpg") }
                .mapKeys { (path, _) -> path.removePrefix("photos/").removeSuffix(".jpg") }
            require(entries.keys.all { it == "data.json" || it == DATABASE_ENTRY || (it.startsWith("photos/") && it.endsWith(".jpg")) }) {
                "Архив содержит неизвестные файлы"
            }
        } else {
            root = manifest
            databaseBytes = null
            photos = entries.filterKeys { it.startsWith("photos/") && it.endsWith(".jpg") }
                .mapKeys { (path, _) -> path.removePrefix("photos/").removeSuffix(".jpg") }
            require(entries.keys.all { it.startsWith("photos/") && it.endsWith(".jpg") }) {
                "Архив содержит неизвестные файлы"
            }
        }

        val addresses = fromJson(root, version)
        val submissions = if (version >= 4) submissionsFromJson(root) else emptyList()
        val metadata = if (version >= 4) BackupMetadataStore.fromJson(root.optJSONObject("metadata")) else BackupMetadata()
        validate(addresses, submissions)
        BackupMetadataStore.validate(metadata, addresses)

        val readings = addresses.flatMap { it.meters }.flatMap { it.readings }
        val readingIds = readings.map { it.id }.toSet()
        val expectedPhotoIds = readings.filter { it.photoUri?.startsWith("backup://") == true }.map { it.id }.toSet()
        require(photos.keys.all { it in readingIds }) { "Архив содержит фото без соответствующего показания" }
        require(expectedPhotoIds == photos.keys) { "Резервная копия неполная: набор фото не совпадает с manifest" }
        return BackupPayload(addresses, photos, submissions, databaseBytes, metadata)
    }

    fun prepareRestorePhotos(context: Context, payload: BackupPayload): PreparedRestore {
        validateDatabaseSnapshot(context, payload)
        if (payload.photos.isEmpty()) return PreparedRestore(payload.addresses, null)
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val required = payload.photos.values.sumOf { it.size.toLong() } + 1024L * 1024L
        val available = runCatching { StatFs(pictures.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        require(available >= required) { "Недостаточно свободного места для восстановления фото" }

        // Never delete old restore directories here. A previously successful restore may still
        // reference those files from the live Room DB. Orphan cleanup must prove that no live
        // reading references a directory before removing it; leaking a staging directory is safer
        // than deleting committed user photos after a later failed restore.
        val root = File(pictures, "restore_staging_${System.currentTimeMillis()}_${UUID.randomUUID()}")
        require(root.mkdirs()) { "Не удалось подготовить временную область восстановления" }
        return try {
            val photoUris = payload.photos.mapValues { (readingId, data) ->
                val file = File(root, "$readingId.jpg")
                file.outputStream().use { it.write(data) }
                require(file.length() == data.size.toLong()) { "Не удалось полностью записать фото $readingId" }
                require(sha256(file.readBytes()) == sha256(data)) { "Ошибка проверки восстановленного фото $readingId" }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            }
            val addresses = payload.addresses.map { address ->
                address.copy(meters = address.meters.map { meter ->
                    meter.copy(readings = meter.readings.map { reading ->
                        reading.copy(photoUri = photoUris[reading.id])
                    })
                })
            }
            PreparedRestore(addresses, root)
        } catch (t: Throwable) {
            root.deleteRecursively()
            throw t
        }
    }

    fun abortPreparedRestore(prepared: PreparedRestore) {
        prepared.rootDir?.deleteRecursively()
    }

    @Deprecated("Use prepareRestorePhotos so a failed database restore can clean the prepared files")
    fun materializePhotos(context: Context, payload: BackupPayload): List<Address> =
        prepareRestorePhotos(context, payload).addresses

    fun rememberLocalGeneration(context: Context, bytes: ByteArray): Int {
        require(bytes.isNotEmpty()) { "Пустая резервная копия" }
        val dir = File(context.filesDir, "backup-generations")
        require(dir.exists() || dir.mkdirs()) { "Не удалось создать локальное хранилище резервных копий" }
        val temp = File(dir, ".pending-${UUID.randomUUID()}.zip")
        temp.writeBytes(bytes)
        val target = File(dir, "backup-${System.currentTimeMillis()}.zip")
        require(temp.renameTo(target)) { "Не удалось зафиксировать локальную резервную копию" }
        val backups = dir.listFiles { file -> file.isFile && file.name.startsWith("backup-") && file.name.endsWith(".zip") }
            .orEmpty().sortedByDescending { it.lastModified() }
        backups.drop(LOCAL_BACKUP_LIMIT).forEach { it.delete() }
        File(dir, "last-successful.txt").writeText(target.name, StandardCharsets.UTF_8)
        return backups.take(LOCAL_BACKUP_LIMIT).size
    }

    fun localGenerationCount(context: Context): Int {
        val dir = File(context.filesDir, "backup-generations")
        return dir.listFiles { file -> file.isFile && file.name.startsWith("backup-") && file.name.endsWith(".zip") }
            ?.size?.coerceAtMost(LOCAL_BACKUP_LIMIT) ?: 0
    }

    fun csv(addresses: List<Address>): String {
        val sb = StringBuilder("Адрес;Лицевой счёт;Получатель;Счётчик;Серийный номер;Дата;Зона;Показание;Единица;Примечание\r\n")
        addresses.forEach { a -> a.meters.forEach { m -> m.readings.sortedBy { it.timestamp }.forEach { r ->
            readingZoneValues(m, r).forEach { (zone, value) ->
                val fields = listOf(a.name, m.account.ifBlank { a.account }, m.recipient.ifBlank { a.recipient }, m.name, m.serial, isoDate(r.timestamp), zone, value, m.unit, r.note)
                sb.append(fields.joinToString(";") { csvCell(it) }).append("\r\n")
            }
        } } }
        return "\uFEFF$sb"
    }

    fun transmissionText(address: Address): String {
        val lines = mutableListOf("Показания счётчиков", address.name)
        if (address.account.isNotBlank()) lines += "Лицевой счёт: ${address.account}"
        if (address.recipient.isNotBlank()) lines += "Получатель: ${address.recipient}"
        address.meters.filter { it.status != "closed" }.forEach { m ->
            val last = m.readings.maxByOrNull { it.timestamp } ?: return@forEach
            val account = m.account.ifBlank { address.account }
            val recipient = m.recipient.ifBlank { address.recipient }
            val meta = buildList { if (account.isNotBlank()) add("л/с $account"); if (recipient.isNotBlank()) add(recipient) }
            val values = readingZoneValues(m, last).entries.joinToString("; ") { (zone, value) -> if (zone == "TOTAL") value else "$zone $value" }
            lines += "${m.name}${m.serial.takeIf { it.isNotBlank() }?.let { " №$it" } ?: ""}: $values ${m.unit}${if(meta.isEmpty()) "" else " (${meta.joinToString(", ")})"}"
        }
        lines += "Подготовлено приложением «Мои счётчики». Передача в УК/РСО выполняется пользователем."
        return lines.joinToString("\n")
    }

    private fun readingZoneValues(meter: Meter, reading: Reading): LinkedHashMap<String, String> {
        val source = if (reading.zoneValues.isNotEmpty()) reading.zoneValues else mapOf("TOTAL" to MeterHistory.readingText(reading))
        val zones = MeterZones.normalize(if (meter.tariffZones.isNotEmpty()) meter.tariffZones else source.keys)
        return linkedMapOf<String, String>().apply {
            zones.forEach { zone -> source[zone]?.let { put(zone, it) } }
            source.forEach { (zone, value) -> if (zone !in this) put(zone, value) }
        }
    }

    private fun createDatabaseSnapshot(context: Context, addresses: List<Address>, submissions: List<Submission>): ByteArray {
        val name = "backup-snapshot-${UUID.randomUUID()}.db"
        var snapshotDb: MeterDatabase? = null
        return try {
            snapshotDb = MeterDatabase.openTemporary(context, name)
            MeterRepository(context, snapshotDb, false).restoreValidated(addresses, submissions)
            snapshotDb.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                require(!cursor.moveToFirst()) { "Не удалось создать согласованный снимок Room DB" }
            }
            snapshotDb.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Снимок Room DB не прошёл integrity_check" }
            }
            snapshotDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            snapshotDb.close()
            snapshotDb = null
            val file = context.getDatabasePath(name)
            require(file.isFile && file.length() > 0L) { "Не удалось создать снимок Room DB" }
            val bytes = file.readBytes()
            require(bytes.size <= MAX_DATABASE_BYTES && isSQLiteDatabase(bytes)) { "Снимок Room DB повреждён" }
            bytes
        } finally {
            runCatching { snapshotDb?.close() }
            context.deleteDatabase(name)
        }
    }

    private fun validateDatabaseSnapshot(context: Context, payload: BackupPayload) {
        val bytes = payload.databaseBytes ?: return
        require(bytes.size <= MAX_DATABASE_BYTES && isSQLiteDatabase(bytes)) { "Снимок Room DB повреждён" }
        val name = "restore-snapshot-${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(name)
        val parent = dbFile.parentFile ?: error("Не удалось подготовить временную базу")
        require(parent.exists() || parent.mkdirs()) { "Не удалось подготовить временную базу" }
        val available = runCatching { StatFs(parent.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        require(available >= bytes.size.toLong() + 1024L * 1024L) { "Недостаточно свободного места для проверки базы данных" }
        var snapshotDb: MeterDatabase? = null
        try {
            dbFile.outputStream().use { it.write(bytes) }
            require(dbFile.length() == bytes.size.toLong()) { "Не удалось полностью записать временную базу" }
            snapshotDb = MeterDatabase.openTemporary(context, name)
            val dao = snapshotDb.meterDao()
            snapshotDb.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                require(!cursor.moveToFirst()) { "В снимке Room DB нарушены связи данных" }
            }
            snapshotDb.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Снимок Room DB не прошёл integrity_check" }
            }

            val expectedAddressIds = payload.addresses.map { it.id }.toSet()
            val expectedMeters = payload.addresses.flatMap { it.meters }
            val expectedMeterIds = expectedMeters.map { it.id }.toSet()
            val expectedPointIds = expectedMeters.map { it.meteringPointId ?: it.id }.toSet()
            val expectedReadings = expectedMeters.flatMap { it.readings }
            val expectedReadingIds = expectedReadings.map { it.id }.toSet()
            val expectedSubmissionIds = payload.submissions.map { it.id }.toSet()

            require(dao.addresses().map { it.id }.toSet() == expectedAddressIds) { "Снимок Room DB не совпадает с адресами резервной копии" }
            require(dao.meters().map { it.id }.toSet() == expectedMeterIds) { "Снимок Room DB не совпадает со счётчиками резервной копии" }
            require(dao.meteringPoints().map { it.id }.toSet() == expectedPointIds) { "Снимок Room DB не совпадает с точками учёта" }
            require(dao.readings().map { it.id }.toSet() == expectedReadingIds) { "Снимок Room DB не совпадает с показаниями резервной копии" }
            require(dao.submissions().map { it.id }.toSet() == expectedSubmissionIds) { "Снимок Room DB не совпадает с передачами резервной копии" }

            val expectedValues = expectedMeters.flatMap { meter ->
                meter.readings.flatMap { reading -> readingZoneValues(meter, reading).map { (zone, value) -> Triple(reading.id, zone, value) } }
            }.toSet()
            val actualValues = dao.readingValues().map { Triple(it.readingId, it.zone, it.valueText) }.toSet()
            require(actualValues == expectedValues) { "Снимок Room DB содержит другие значения показаний" }

            val expectedItems = payload.submissions.flatMap { submission ->
                submission.items.map { item -> listOf(submission.id, item.meteringPointId, item.meterId, item.zone.uppercase(Locale.US), item.valueText).joinToString("\u0000") }
            }.toSet()
            val actualItems = dao.submissionItems().map { item ->
                listOf(item.submissionId, item.meteringPointId, item.meterId, item.zone.uppercase(Locale.US), item.valueText).joinToString("\u0000")
            }.toSet()
            require(actualItems == expectedItems) { "Снимок Room DB содержит другие переданные значения" }
        } finally {
            runCatching { snapshotDb?.close() }
            context.deleteDatabase(name)
        }
    }

    private fun toJson(addresses: List<Address>, submissions: List<Submission>, metadata: BackupMetadata): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("metadata", BackupMetadataStore.toJson(metadata))
        put("addresses", JSONArray().apply { addresses.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("name", a.name); put("account", a.account); put("recipient", a.recipient)
            put("meters", JSONArray().apply { a.meters.forEach { m -> put(JSONObject().apply {
                put("id", m.id); put("name", m.name); put("unit", m.unit); put("kind", m.kind); put("serial", m.serial); put("location", m.location)
                put("integerDigits", m.integerDigits); put("fractionDigits", m.fractionDigits); put("previousMeterId", m.previousMeterId); put("meteringPointId", m.meteringPointId)
                put("installedAt", m.installedAt); put("verificationUntil", m.verificationUntil); put("status", m.status); put("account", m.account); put("recipient", m.recipient)
                put("tariffZones", JSONArray().apply { MeterZones.normalize(m.tariffZones).forEach(::put) })
                put("tariffSchedule", JSONArray().apply { m.tariffSchedule.sortedBy { it.validFrom }.forEach { t -> put(JSONObject().apply { put("zone", t.zone); put("validFrom", t.validFrom); put("priceText", t.priceText) }) } })
                put("readings", JSONArray().apply { m.readings.forEach { r -> put(JSONObject().apply {
                    put("id", r.id); put("valueText", MeterHistory.readingText(r)); put("timestamp", r.timestamp); put("note", r.note); put("rollover", r.rollover); put("hasPhoto", r.photoUri != null); put("billingPeriod", r.billingPeriod)
                    put("zoneValues", JSONObject().apply { readingZoneValues(m, r).forEach { (zone, value) -> put(zone, value) } })
                }) } })
            }) } })
        }) } })
        put("submissions", JSONArray().apply { submissions.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("addressId", s.addressId); put("billingPeriod", s.billingPeriod); put("submittedAt", s.submittedAt); put("recipient", s.recipient); put("snapshotText", s.snapshotText)
            put("items", JSONArray().apply { s.items.forEach { item -> put(JSONObject().apply {
                put("meteringPointId", item.meteringPointId); put("meterId", item.meterId); put("zone", item.zone); put("valueText", item.valueText)
            }) } })
        }) } })
    }

    private fun fromJson(root: JSONObject, version: Int): List<Address> {
        val aa = root.getJSONArray("addresses")
        val parsed = List(aa.length()) { i ->
            val a = aa.getJSONObject(i)
            val ma = a.optJSONArray("meters") ?: JSONArray()
            Address(
                id = a.getString("id"),
                name = a.getString("name"),
                account = a.optString("account"),
                recipient = a.optString("recipient"),
                meters = List(ma.length()) { j ->
                    val m = ma.getJSONObject(j)
                    val ra = m.optJSONArray("readings") ?: JSONArray()
                    val zones = if (version >= 2) m.optJSONArray("tariffZones")?.let { arr -> List(arr.length()) { idx -> arr.getString(idx) } } ?: MeterZones.SINGLE else MeterZones.SINGLE
                    val schedule = if (version >= 2) m.optJSONArray("tariffSchedule")?.let { arr -> List(arr.length()) { idx ->
                        val t = arr.getJSONObject(idx); TariffScheduleEntry(t.getString("zone"), t.getLong("validFrom"), t.getString("priceText"))
                    } } ?: emptyList() else emptyList()
                    Meter(
                        id = m.getString("id"), name = m.getString("name"), unit = m.getString("unit"), kind = m.optString("kind", "other"), serial = m.optString("serial"),
                        integerDigits = m.optIntOrNull("integerDigits"), fractionDigits = m.optIntOrNull("fractionDigits"), previousMeterId = m.optNullableString("previousMeterId"),
                        installedAt = m.optLongOrNull("installedAt"), verificationUntil = m.optLongOrNull("verificationUntil"), status = m.optNullableString("status"), account = m.optString("account"), recipient = m.optString("recipient"),
                        tariffZones = MeterZones.normalize(zones), tariffSchedule = schedule, location = if (version >= 3) m.optString("location") else "", meteringPointId = if (version >= 4) m.optNullableString("meteringPointId") else null,
                        readings = List(ra.length()) { k ->
                            val r = ra.getJSONObject(k)
                            val zoneValues = if (version >= 2 && r.has("zoneValues")) {
                                val obj = r.getJSONObject("zoneValues")
                                linkedMapOf<String, String>().apply {
                                    obj.keys().forEach { zone ->
                                        val raw = obj.getString(zone)
                                        require(MeterHistory.normalizeReadingText(raw) == raw) { "Некорректное показание" }
                                        put(zone.uppercase(Locale.US), raw)
                                    }
                                }
                            } else {
                                val raw = r.getString("valueText")
                                require(MeterHistory.normalizeReadingText(raw) == raw) { "Некорректное показание" }
                                linkedMapOf("TOTAL" to raw)
                            }
                            require(zoneValues.isNotEmpty()) { "Показание не содержит тарифных зон" }
                            val primary = zoneValues["TOTAL"] ?: zoneValues["T1"] ?: zoneValues.values.first()
                            val period = if (version >= 4) r.optNullableString("billingPeriod") else null
                            Reading(
                                id = r.getString("id"), value = primary.toDouble(), valueText = primary, timestamp = r.getLong("timestamp"), note = r.optString("note"), rollover = r.optBoolean("rollover"),
                                photoUri = if (r.optBoolean("hasPhoto")) "backup://${r.getString("id")}" else null, zoneValues = zoneValues, billingPeriod = period
                            )
                        }
                    )
                }
            )
        }
        if (version >= 4) return parsed
        return parsed.map { address ->
            val byId = address.meters.associateBy { it.id }
            fun rootOf(meter: Meter): String {
                var current = meter
                val seen = mutableSetOf<String>()
                while (true) {
                    require(seen.add(current.id)) { "Циклическая цепочка замены в старой резервной копии" }
                    val previousId = current.previousMeterId ?: return current.id
                    current = byId[previousId]
                        ?: error("Старая резервная копия содержит неполную или межадресную цепочку замены")
                }
            }
            address.copy(meters = address.meters.map { meter -> meter.copy(meteringPointId = rootOf(meter)) })
        }
    }

    private fun submissionsFromJson(root: JSONObject): List<Submission> {
        val source = root.optJSONArray("submissions") ?: JSONArray()
        return List(source.length()) { i ->
            val s = source.getJSONObject(i)
            val items = s.optJSONArray("items") ?: JSONArray()
            Submission(
                id = s.getString("id"), addressId = s.getString("addressId"), billingPeriod = s.getString("billingPeriod"), submittedAt = s.getLong("submittedAt"), recipient = s.optString("recipient"), snapshotText = s.optString("snapshotText"),
                items = List(items.length()) { j ->
                    val item = items.getJSONObject(j)
                    SubmissionItem(item.getString("meteringPointId"), item.getString("meterId"), item.optString("zone", "TOTAL").uppercase(Locale.US), item.getString("valueText"))
                }
            )
        }
    }

    private fun validate(addresses: List<Address>, submissions: List<Submission> = emptyList()) {
        val addressIds = addresses.map { it.id }
        require(addressIds.size == addressIds.toSet().size) { "Повторяющиеся ID адресов" }
        val meters = addresses.flatMap { it.meters }
        val meterIds = meters.map { it.id }
        require(meterIds.size == meterIds.toSet().size) { "Повторяющиеся ID счётчиков" }
        val readings = meters.flatMap { it.readings }
        val readingIds = readings.map { it.id }
        require(readingIds.size == readingIds.toSet().size) { "Повторяющиеся ID показаний" }
        require(addresses.all { it.name.isNotBlank() } && meters.all { it.name.isNotBlank() && it.unit.isNotBlank() }) { "Не заполнены обязательные поля" }
        require(meters.all { it.previousMeterId == null || it.previousMeterId in meterIds }) { "Нарушена цепочка замены счётчиков" }
        require(meters.all { m -> m.readings.all { r -> readingZoneValues(m, r).isNotEmpty() } }) { "Пустое многотарифное показание" }
        readings.mapNotNull { it.billingPeriod }.forEach { require(runCatching { YearMonth.parse(it) }.isSuccess) { "Некорректный отчётный период" } }

        val meterAddress = addresses.flatMap { a -> a.meters.map { it.id to a.id } }.toMap()
        meters.forEach { meter ->
            meter.previousMeterId?.let { previousId ->
                require(meterAddress[previousId] == meterAddress[meter.id]) { "Цепочка замены пересекает адреса" }
            }
        }
        val pointOwner = mutableMapOf<String, String>()
        addresses.forEach { address -> address.meters.forEach { meter ->
            val pointId = meter.meteringPointId ?: meter.id
            val oldOwner = pointOwner.putIfAbsent(pointId, address.id)
            require(oldOwner == null || oldOwner == address.id) { "Точка учёта используется в разных адресах" }
        } }
        val submissionIds = submissions.map { it.id }
        require(submissionIds.size == submissionIds.toSet().size) { "Повторяющиеся ID передач" }
        submissions.forEach { submission ->
            require(submission.addressId in addressIds) { "Передача ссылается на неизвестный адрес" }
            require(runCatching { YearMonth.parse(submission.billingPeriod) }.isSuccess) { "Некорректный отчётный период передачи" }
            require(submission.items.isNotEmpty()) { "Передача не содержит показаний" }
            val itemKeys = mutableSetOf<Pair<String, String>>()
            submission.items.forEach { item ->
                require(item.meterId.isNotBlank()) { "Передача содержит пустой идентификатор счётчика" }
                require(item.meteringPointId.isNotBlank()) { "Передача содержит пустую точку учёта" }
                meterAddress[item.meterId]?.let { owner ->
                    require(owner == submission.addressId) { "Передача содержит счётчик другого адреса" }
                }
                pointOwner[item.meteringPointId]?.let { owner ->
                    require(owner == submission.addressId) { "Передача содержит точку учёта другого адреса" }
                }
                val zone = item.zone.uppercase(Locale.US)
                require(zone in setOf("TOTAL", "T1", "T2", "T3")) { "Некорректная тарифная зона в передаче" }
                require(MeterHistory.normalizeReadingText(item.valueText) == item.valueText) { "Некорректное показание в передаче" }
                require(itemKeys.add(item.meteringPointId to zone)) { "Повторяющаяся зона в передаче" }
            }
        }
    }

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it.isBlank() }

    private fun isSQLiteDatabase(bytes: ByteArray): Boolean =
        bytes.size >= 100 && bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII))

    private fun sqliteHeaderStub(): ByteArray = ByteArray(100).also { target ->
        "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(target)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

    private fun csvCell(v: String) = "\"${v.replace("\"", "\"\"")}\""
    private fun isoDate(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ts))
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) { val n = read(buf); if (n < 0) break; total += n; require(total <= max) { "Слишком большой файл в архиве" }; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    private fun JSONObject.optNullableString(key: String) = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.optIntOrNull(key: String) = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.optLongOrNull(key: String) = if (!has(key) || isNull(key)) null else getLong(key)
}
