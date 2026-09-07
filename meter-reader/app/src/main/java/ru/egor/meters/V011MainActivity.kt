package ru.egor.meters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID

private val A11 = Color(0xFF6B5BC7)
private val BG11 = Color(0xFFF7F7FA)
private val M11 = Color(0xFF77737F)
private val D11 = Color(0xFFC33E49)
private val Scheme11 = lightColorScheme(primary = A11, background = BG11, surface = Color.White, error = D11)
private const val MAX_BACKUP_IMPORT_BYTES_11 = 256 * 1024 * 1024

private data class Preset11(val title: String, val unit: String, val kind: String)
private val presets11 = listOf(
    Preset11("Холодная вода", "м³", "cold_water"),
    Preset11("Горячая вода", "м³", "hot_water"),
    Preset11("Электричество", "кВт·ч", "electricity"),
    Preset11("Газ", "м³", "gas"),
    Preset11("Отопление", "Гкал", "heating"),
    Preset11("Другой", "ед.", "other")
)

class V011MainActivity : ComponentActivity() {
    private var pendingPhoto: Uri? = null
    private var photoCallback: ((String?) -> Unit)? = null
    private var pendingBackup: ByteArray? = null
    private var pendingCsv: String? = null
    private var restoreCallback: ((Result<BackupPayload>) -> Unit)? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (!ok && uri != null) runCatching { contentResolver.delete(uri, null, null) }
        photoCallback?.invoke(if (ok) uri?.toString() else null)
        pendingPhoto = null
        photoCallback = null
    }

    private val backupSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingBackup
        pendingBackup = null
        if (uri == null || bytes == null) return@registerForActivityResult
        runCatching {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть выбранный файл")
            stream.use {
                it.write(bytes)
                it.flush()
            }
            verifySafWrite11(uri, bytes)
        }.onSuccess {
            Toast.makeText(this, "Резервная копия сохранена и проверена", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Не удалось сохранить резервную копию", Toast.LENGTH_LONG).show()
        }
    }

    private val csvSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingCsv
        pendingCsv = null
        if (uri == null || text == null) return@registerForActivityResult
        runCatching {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть выбранный файл")
            stream.use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        }.onSuccess {
            Toast.makeText(this, "CSV сохранён", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Не удалось сохранить CSV", Toast.LENGTH_LONG).show()
        }
    }

    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val result: Result<BackupPayload> = if (uri == null) {
            Result.failure(IllegalArgumentException("Файл не выбран"))
        } else {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesLimited11(MAX_BACKUP_IMPORT_BYTES_11) }
                    ?: error("Не удалось прочитать файл")
                MeterTransfer.parseBackup(bytes)
            }
        }
        restoreCallback?.invoke(result)
        restoreCallback = null
    }

    private fun verifySafWrite11(uri: Uri, expected: ByteArray) {
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        val actualDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val stream = contentResolver.openInputStream(uri) ?: error("Файл создан, но не удалось проверить запись")
        stream.use {
            val buffer = ByteArray(8192)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= expected.size.toLong()) { "Записанный файл имеет неожиданный размер" }
                actualDigest.update(buffer, 0, read)
            }
        }
        require(total == expected.size.toLong()) { "Резервная копия записана не полностью" }
        require(actualDigest.digest().contentEquals(expectedDigest)) { "Резервная копия записана с ошибкой" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MeterRepository(this)
        setContent {
            MaterialTheme(colorScheme = Scheme11) {
                App11(
                    repo = repo,
                    takePhoto = { callback ->
                        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                        val file = File(dir, "meter_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                        pendingPhoto = uri
                        photoCallback = callback
                        camera.launch(uri)
                    },
                    saveBackup = { data ->
                        runCatching {
                            val bytes = MeterTransfer.createBackup(this, data, repo.submissions())
                            MeterTransfer.rememberLocalGeneration(this, bytes)
                            bytes
                        }.onSuccess { bytes ->
                            pendingBackup = bytes
                            backupSaver.launch("moi-schetschiki-backup.zip")
                        }.onFailure {
                            Toast.makeText(this, it.message ?: "Не удалось создать резервную копию", Toast.LENGTH_LONG).show()
                        }
                    },
                    pickBackup = { callback ->
                        restoreCallback = callback
                        backupPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    saveCsv = { data ->
                        runCatching { MeterTransfer.csv(data) }
                            .onSuccess { csv -> pendingCsv = csv; csvSaver.launch("moi-schetschiki-history.csv") }
                            .onFailure { Toast.makeText(this, it.message ?: "Не удалось подготовить CSV", Toast.LENGTH_LONG).show() }
                    },
                    openReminders = { startActivity(Intent(this, V010MainActivity::class.java)) },
                    openLegacyTools = { startActivity(Intent(this, V07MainActivity::class.java)) }
                )
            }
        }
    }
}

@Composable
private fun App11(
    repo: MeterRepository,
    takePhoto: (((String?) -> Unit) -> Unit),
    saveBackup: (List<Address>) -> Unit,
    pickBackup: ((Result<BackupPayload>) -> Unit) -> Unit,
    saveCsv: (List<Address>) -> Unit,
    openReminders: () -> Unit,
    openLegacyTools: () -> Unit
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf(repo.load()) }
    var addressId by remember { mutableStateOf<String?>(null) }
    var meterId by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BackupPayload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun save(value: List<Address>) {
        repo.save(value)
        data = repo.load()
    }

    val address = data.firstOrNull { it.id == addressId }
    val meter = address?.meters?.firstOrNull { it.id == meterId }

    Surface(Modifier.fillMaxSize(), color = BG11) {
        when {
            tools -> Tools11(
                data = data,
                back = { tools = false },
                backup = { saveBackup(data) },
                restore = {
                    pickBackup { result ->
                        result.onSuccess { pendingRestore = it }
                            .onFailure { message = it.message ?: "Файл повреждён" }
                    }
                },
                csv = { saveCsv(data) },
                reminders = openReminders,
                legacyTools = openLegacyTools
            )

            address == null -> Home11(
                data = data,
                open = { addressId = it },
                add = { save(data + Address(name = it)) },
                delete = { id -> save(data.filterNot { it.id == id }) },
                tools = { tools = true }
            )

            meter == null -> Address11(
                address = address,
                back = { addressId = null },
                open = { meterId = it },
                add = { preset, name, serial, zones ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(
                            meters = a.meters + Meter(name = name, unit = preset.unit, kind = preset.kind, serial = serial, tariffZones = zones)
                        ) else a
                    })
                },
                editAddress = { changed -> save(data.map { if (it.id == changed.id) changed else it }) },
                editMeter = { changed ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.map { if (it.id == changed.id) changed else it }) else a
                    })
                },
                replace = { old, serial, values ->
                    val now = System.currentTimeMillis()
                    val primary = values["TOTAL"] ?: values["T1"] ?: values.values.first()
                    val replacement = Meter(
                        name = old.name,
                        unit = old.unit,
                        kind = old.kind,
                        serial = serial,
                        readings = listOf(Reading(value = primary.toDouble(), valueText = primary, timestamp = now, zoneValues = values)),
                        integerDigits = old.integerDigits,
                        fractionDigits = old.fractionDigits,
                        previousMeterId = old.id,
                        installedAt = now,
                        status = "active",
                        account = old.account,
                        recipient = old.recipient,
                        tariffZones = old.tariffZones,
                        tariffSchedule = old.tariffSchedule
                    )
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(
                            meters = a.meters.map { if (it.id == old.id) it.copy(status = "closed") else it } + replacement
                        ) else a
                    })
                },
                delete = { target ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.filterNot { it.id == target.id }) else a
                    })
                }
            )

            else -> Meter11(
                meter = meter,
                back = { meterId = null },
                takePhoto = takePhoto,
                add = { reading ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.map { if (it.id == meter.id) it.copy(readings = it.readings + reading) else it }) else a
                    })
                },
                edit = { reading ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.map { m ->
                            if (m.id == meter.id) m.copy(readings = m.readings.map { if (it.id == reading.id) reading else it }) else m
                        }) else a
                    })
                },
                delete = { id ->
                    save(data.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.map { m ->
                            if (m.id == meter.id) m.copy(readings = m.readings.filterNot { it.id == id }) else m
                        }) else a
                    })
                }
            )
        }
    }

    pendingRestore?.let { payload ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Восстановить резервную копию?") },
            text = { Text("Текущие данные будут заменены только после подтверждения. Архив уже проверен.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val prepared = MeterTransfer.prepareRestorePhotos(context, payload)
                        try {
                            repo.restoreValidated(prepared.addresses, payload.submissions)
                        } catch (t: Throwable) {
                            MeterTransfer.abortPreparedRestore(prepared)
                            throw t
                        }
                        val metadataError = runCatching {
                            BackupMetadataStore.apply(context, payload.metadata, prepared.addresses)
                        }.exceptionOrNull()
                        data = repo.load()
                        addressId = null
                        meterId = null
                        tools = false
                        pendingRestore = null
                        message = if (metadataError == null) {
                            "Резервная копия восстановлена"
                        } else {
                            "Основные данные восстановлены, но настройки шаблонов/напоминаний восстановить не удалось: ${metadataError.message ?: "ошибка"}"
                        }
                    }.onFailure { message = it.message ?: "Не удалось восстановить данные" }
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Отмена") } }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Мои счётчики") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun Frame11(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
    content = content
)

@Composable private fun Back11(text: String, onClick: () -> Unit) = TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text("‹  $text") }
@Composable private fun Primary11(text: String, onClick: () -> Unit) = Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }

@Composable
private fun Home11(data: List<Address>, open: (String) -> Unit, add: (String) -> Unit, delete: (String) -> Unit, tools: () -> Unit) {
    var addDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Address?>(null) }
    Frame11 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Мои счётчики", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("Одно- и многотарифные показания", color = M11, fontSize = 13.sp)
            }
            TextButton(onClick = tools) { Text("Данные") }
        }
        Spacer(Modifier.height(14.dp))
        if (data.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Добавьте первый адрес", color = M11) }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(data, key = { it.id }) { address ->
                    Card(onClick = { open(address.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(address.name, fontWeight = FontWeight.SemiBold)
                                Text("${address.meters.count { it.status != "closed" }} активных счётчиков", color = M11, fontSize = 12.sp)
                            }
                            TextButton(onClick = { deleting = address }) { Text("Удалить", color = D11, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Primary11("Добавить адрес") { addDialog = true }
    }
    if (addDialog) TextDialog11("Новый адрес", "Название или адрес", { addDialog = false }) { add(it); addDialog = false }
    deleting?.let { address ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить адрес?") },
            text = { Text("Все счётчики и показания по адресу «${address.name}» будут удалены.") },
            confirmButton = { TextButton(onClick = { delete(address.id); deleting = null }) { Text("Удалить", color = D11) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun Address11(
    address: Address,
    back: () -> Unit,
    open: (String) -> Unit,
    add: (Preset11, String, String, List<String>) -> Unit,
    editAddress: (Address) -> Unit,
    editMeter: (Meter) -> Unit,
    replace: (Meter, String, Map<String, String>) -> Unit,
    delete: (Meter) -> Unit
) {
    var addDialog by remember { mutableStateOf(false) }
    var metadataDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Meter?>(null) }
    var replacing by remember { mutableStateOf<Meter?>(null) }
    var deleting by remember { mutableStateOf<Meter?>(null) }

    Frame11 {
        Back11("Все адреса", back)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(address.name, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("${address.meters.size} счётчиков", color = M11)
            }
            TextButton(onClick = { metadataDialog = true }) { Text("Реквизиты") }
        }
        Spacer(Modifier.height(12.dp))
        if (address.meters.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Добавьте счётчик", color = M11) }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(address.meters, key = { it.id }) { meter ->
                    val latest = meter.readings.maxByOrNull { it.timestamp }
                    Card(onClick = { open(meter.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(meter.name, fontWeight = FontWeight.SemiBold)
                                    if (meter.serial.isNotBlank()) Text("№ ${meter.serial}", color = M11, fontSize = 11.sp)
                                    Text(if (meter.tariffZones == MeterZones.SINGLE) "Однотарифный" else meter.tariffZones.joinToString(" / "), color = M11, fontSize = 12.sp)
                                    if (meter.status == "closed") Text("Заменён · история сохранена", color = M11, fontSize = 11.sp)
                                }
                                if (meter.status != "closed") TextButton(onClick = { replacing = meter }) { Text("Заменить", fontSize = 11.sp) }
                                TextButton(onClick = { editing = meter }) { Text("Изм.", fontSize = 11.sp) }
                                TextButton(onClick = { deleting = meter }) { Text("Удал.", color = D11, fontSize = 11.sp) }
                            }
                            if (latest != null) Text(displayZones11(meter, latest), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Primary11("Добавить счётчик") { addDialog = true }
    }

    if (addDialog) AddMeter11({ addDialog = false }) { preset, name, serial, zones -> add(preset, name, serial, zones); addDialog = false }
    if (metadataDialog) AddressMetadata11(address, { metadataDialog = false }) { editAddress(it); metadataDialog = false }
    editing?.let { meter -> MeterMetadata11(meter, { editing = null }) { editMeter(it); editing = null } }
    replacing?.let { meter -> Replacement11(meter, { replacing = null }) { serial, values -> replace(meter, serial, values); replacing = null } }
    deleting?.let { meter ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить счётчик?") },
            text = { Text("История «${meter.name}» будет удалена.") },
            confirmButton = { TextButton(onClick = { delete(meter); deleting = null }) { Text("Удалить", color = D11) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun AddMeter11(dismiss: () -> Unit, confirm: (Preset11, String, String, List<String>) -> Unit) {
    var preset by remember { mutableStateOf(presets11.first()) }
    var name by remember { mutableStateOf(preset.title) }
    var serial by remember { mutableStateOf("") }
    var tariff by remember { mutableIntStateOf(1) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Новый счётчик", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                presets11.forEach { item ->
                    FilterChip(selected = item.kind == preset.kind, onClick = { preset = item; name = item.title; if (item.kind != "electricity") tariff = 1 }, label = { Text(item.title) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(serial, { serial = it.take(40) }, label = { Text("Серийный номер") }, singleLine = true)
                if (preset.kind == "electricity") {
                    Spacer(Modifier.height(12.dp))
                    Text("Тарифные зоны", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = tariff == 1, onClick = { tariff = 1 }, label = { Text("1 тариф") })
                        FilterChip(selected = tariff == 2, onClick = { tariff = 2 }, label = { Text("T1/T2") })
                        FilterChip(selected = tariff == 3, onClick = { tariff = 3 }, label = { Text("T1/T2/T3") })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(
                        onClick = {
                            val zones = when (tariff) { 2 -> MeterZones.TWO_TARIFF; 3 -> MeterZones.THREE_TARIFF; else -> MeterZones.SINGLE }
                            confirm(preset, name.trim(), serial.trim(), zones)
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Добавить") }
                }
            }
        }
    }
}

@Composable
private fun AddressMetadata11(address: Address, dismiss: () -> Unit, confirm: (Address) -> Unit) {
    var account by remember { mutableStateOf(address.account) }
    var recipient by remember { mutableStateOf(address.recipient) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Реквизиты адреса") },
        text = { Column { OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт") }); Spacer(Modifier.height(8.dp)); OutlinedTextField(recipient, { recipient = it }, label = { Text("Получатель") }) } },
        confirmButton = { TextButton(onClick = { confirm(address.copy(account = account.trim(), recipient = recipient.trim())) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MeterMetadata11(meter: Meter, dismiss: () -> Unit, confirm: (Meter) -> Unit) {
    var name by remember { mutableStateOf(meter.name) }
    var serial by remember { mutableStateOf(meter.serial) }
    var account by remember { mutableStateOf(meter.account) }
    var recipient by remember { mutableStateOf(meter.recipient) }
    var tariff by remember { mutableIntStateOf(when (MeterZones.normalize(meter.tariffZones).size) { 2 -> 2; 3 -> 3; else -> 1 }) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Настройки счётчика", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Название") })
                Spacer(Modifier.height(8.dp)); OutlinedTextField(serial, { serial = it }, label = { Text("Серийный номер") })
                Spacer(Modifier.height(8.dp)); OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт") })
                Spacer(Modifier.height(8.dp)); OutlinedTextField(recipient, { recipient = it }, label = { Text("Получатель") })
                if (meter.kind == "electricity") {
                    Spacer(Modifier.height(10.dp)); Text("Тарифные зоны", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = tariff == 1, onClick = { tariff = 1 }, label = { Text("1") })
                        FilterChip(selected = tariff == 2, onClick = { tariff = 2 }, label = { Text("T1/T2") })
                        FilterChip(selected = tariff == 3, onClick = { tariff = 3 }, label = { Text("T1/T2/T3") })
                    }
                    Text("Смена зон не преобразует старые записи автоматически.", color = M11, fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = {
                        val zones = when (tariff) { 2 -> MeterZones.TWO_TARIFF; 3 -> MeterZones.THREE_TARIFF; else -> MeterZones.SINGLE }
                        confirm(meter.copy(name = name.trim(), serial = serial.trim(), account = account.trim(), recipient = recipient.trim(), tariffZones = zones))
                    }, enabled = name.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
private fun Replacement11(meter: Meter, dismiss: () -> Unit, confirm: (String, Map<String, String>) -> Unit) {
    val zones = MeterZones.normalize(meter.tariffZones)
    var serial by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, String>().apply { zones.forEach { put(it, "") } } }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Замена счётчика", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Старый счётчик останется в истории.", color = M11, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(serial, { serial = it.take(40) }, label = { Text("Серийный номер нового") })
                Spacer(Modifier.height(8.dp))
                zones.forEach { zone ->
                    OutlinedTextField(values[zone].orEmpty(), { values[zone] = sanitize11(it); error = null }, label = { Text(if (zone == "TOTAL") "Начальное показание" else "Начальное $zone") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    Spacer(Modifier.height(7.dp))
                }
                error?.let { Text(it, color = D11, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = {
                        val normalized = linkedMapOf<String, String>()
                        for (zone in zones) {
                            val raw = MeterHistory.normalizeReadingText(values[zone])
                            if (raw == null) { error = "Заполните ${if (zone == "TOTAL") "начальное показание" else zone}"; return@Button }
                            normalized[zone] = raw
                        }
                        confirm(serial.trim(), normalized)
                    }, modifier = Modifier.weight(1f)) { Text("Заменить") }
                }
            }
        }
    }
}

@Composable
private fun Meter11(meter: Meter, back: () -> Unit, takePhoto: (((String?) -> Unit) -> Unit), add: (Reading) -> Unit, edit: (Reading) -> Unit, delete: (String) -> Unit) {
    var editing by remember { mutableStateOf<Reading?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Reading?>(null) }
    val sorted = meter.readings.sortedByDescending { it.timestamp }
    val latest = sorted.firstOrNull()
    val previous = sorted.getOrNull(1)
    val latestConsumption = if (latest != null && previous != null) ZoneAnalytics.consumptionBetween(point11(previous), point11(latest), meter.tariffZones) else emptyList()
    val currentMonth = YearMonth.now()
    val monthly = ZoneAnalytics.monthlyConsumption(meter.readings.map(::point11), currentMonth, meter.tariffZones, ZoneId.systemDefault())
    val previousMonthly = ZoneAnalytics.monthlyConsumption(meter.readings.map(::point11), currentMonth.minusMonths(1), meter.tariffZones, ZoneId.systemDefault())
    val comparison = ZoneAnalytics.comparePeriods(monthly, previousMonthly)

    Frame11 {
        Back11("Счётчики", back)
        Text(meter.name, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(if (meter.tariffZones == MeterZones.SINGLE) "Однотарифный" else meter.tariffZones.joinToString(" / "), color = M11, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(16.dp)) {
                Text("Текущее показание", color = M11, fontSize = 12.sp)
                Text(if (latest == null) "—" else displayZones11(meter, latest), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (latestConsumption.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); Text("Расход с прошлого раза", color = M11, fontSize = 12.sp)
                    latestConsumption.forEach { Text("${zoneLabel11(it.zone)}: ${num11(it.amount)} ${meter.unit}", fontSize = 14.sp) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(14.dp)) {
                Text("Расход за ${monthLabel11(currentMonth)}", fontWeight = FontWeight.Bold)
                if (monthly.isEmpty()) Text("Недостаточно точек для расчёта", color = M11, fontSize = 12.sp)
                monthly.forEach { Text("${zoneLabel11(it.zone)}: ${num11(it.amount)} ${meter.unit}", fontSize = 13.sp) }
                if (comparison.any { it.previous != null }) {
                    Spacer(Modifier.height(6.dp)); Text("К предыдущему месяцу", color = M11, fontSize = 11.sp)
                    comparison.filter { it.previous != null }.forEach { item ->
                        val delta = item.delta
                        Text("${zoneLabel11(item.zone)}: ${delta?.let { if (it.signum() >= 0) "+${num11(it)}" else num11(it) } ?: "—"} ${meter.unit}", fontSize = 12.sp)
                    }
                }
            }
        }
        if (ZoneAnalytics.shouldShowGraph(sorted.size)) {
            Spacer(Modifier.height(10.dp))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Column(Modifier.padding(14.dp)) {
                    Text("Динамика", fontWeight = FontWeight.Bold)
                    Text("Показывается только при 6 и более точках", color = M11, fontSize = 11.sp)
                    sorted.take(6).reversed().forEach { reading -> Text("${date11(reading.timestamp)} · ${displayZones11(meter, reading)}", fontSize = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp)); Text("История", fontSize = 19.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
        if (sorted.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(sorted, key = { it.id }) { reading ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(displayZones11(meter, reading), fontWeight = FontWeight.SemiBold)
                                Text("${reading.billingPeriod ?: "период не указан"} · ${date11(reading.timestamp)}", color = M11, fontSize = 11.sp)
                                if (reading.note.isNotBlank()) Text(reading.note, color = M11, fontSize = 11.sp)
                            }
                            TextButton(onClick = { editing = reading }) { Text("Изм.", fontSize = 11.sp) }
                            TextButton(onClick = { deleting = reading }) { Text("Удал.", color = D11, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp)); Primary11("Новое показание") { adding = true }
    }

    if (adding) ReadingDialog11(meter, null, takePhoto, { adding = false }) { add(it); adding = false }
    editing?.let { reading -> ReadingDialog11(meter, reading, takePhoto, { editing = null }) { edit(it); editing = null } }
    deleting?.let { reading ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить показание?") },
            text = { Text(date11(reading.timestamp)) },
            confirmButton = { TextButton(onClick = { delete(reading.id); deleting = null }) { Text("Удалить", color = D11) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ReadingDialog11(meter: Meter, existing: Reading?, takePhoto: (((String?) -> Unit) -> Unit), dismiss: () -> Unit, confirm: (Reading) -> Unit) {
    val context = LocalContext.current
    val zones = if (existing != null && existing.zoneValues.isNotEmpty()) MeterZones.normalize(existing.zoneValues.keys) else MeterZones.normalize(meter.tariffZones)
    val initial = existing?.zoneValues.orEmpty()
    val values = remember(existing?.id) { mutableStateMapOf<String, String>().apply { zones.forEach { zone -> put(zone, initial[zone] ?: if (zone == "TOTAL") existing?.valueText.orEmpty() else "") } } }
    var billingPeriod by remember(existing?.id) { mutableStateOf(existing?.billingPeriod ?: YearMonth.now().toString()) }
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var photo by remember(existing?.id) { mutableStateOf(existing?.photoUri) }
    var error by remember { mutableStateOf<String?>(null) }
    var ocrStatus by remember { mutableStateOf<String?>(null) }
    var recognizing by remember { mutableStateOf(false) }
    val previous = meter.readings.filter { existing == null || it.id != existing.id }.maxByOrNull { it.timestamp }

    Dialog(onDismissRequest = { if (!recognizing) dismiss() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text(if (existing == null) "Новое показание" else "Изменить показание", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(meter.name, color = M11, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = billingPeriod,
                    onValueChange = { billingPeriod = it.filter { ch -> ch.isDigit() || ch == '-' }.take(7); error = null },
                    label = { Text("Расчётный период") },
                    supportingText = { Text("Формат ГГГГ-ММ. Период хранится явно и не меняется из-за часового пояса.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(7.dp))
                zones.forEach { zone ->
                    OutlinedTextField(
                        value = values[zone].orEmpty(),
                        onValueChange = { values[zone] = sanitize11(it); error = null },
                        label = { Text(if (zone == "TOTAL") "Показание" else zone) },
                        suffix = { Text(meter.unit) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(7.dp))
                }
                OutlinedButton(onClick = {
                    if (!recognizing) takePhoto { uri ->
                        photo = uri
                        if (uri != null && zones == MeterZones.SINGLE) {
                            recognizing = true
                            ocrStatus = "Распознаю цифры…"
                            MeterOcr.recognize(context, uri, previous?.value) { result ->
                                recognizing = false
                                result.onSuccess { suggestion ->
                                    if (suggestion == null) ocrStatus = "Не удалось уверенно распознать. Введите вручную."
                                    else {
                                        values["TOTAL"] = MeterHistory.decimalText(suggestion)
                                        ocrStatus = "OCR-подсказка заполнена. Проверьте значение перед сохранением."
                                    }
                                }.onFailure { ocrStatus = "Не удалось распознать. Введите вручную." }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (photo == null) "Сделать фото" else "Переснять фото") }
                if (recognizing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                ocrStatus?.let { Text(it, color = M11, fontSize = 11.sp) }
                if (zones.size > 1) Text("Для T1/T2/T3 каждую зону нужно проверить и подтвердить вручную; фото сохраняется как подтверждение.", color = M11, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Комментарий") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = D11, fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = dismiss, enabled = !recognizing, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = {
                        val period = runCatching { YearMonth.parse(billingPeriod) }.getOrNull()
                        if (period == null || period.toString() != billingPeriod) { error = "Укажите период в формате ГГГГ-ММ"; return@Button }
                        val normalized = linkedMapOf<String, String>()
                        for (zone in zones) {
                            val raw = MeterHistory.normalizeReadingText(values[zone])
                            if (raw == null) { error = "Заполните корректно ${if (zone == "TOTAL") "показание" else zone}"; return@Button }
                            normalized[zone] = raw
                        }
                        val primary = normalized["TOTAL"] ?: normalized["T1"] ?: return@Button
                        confirm(Reading(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            value = primary.toDouble(),
                            timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                            photoUri = photo,
                            note = note.trim(),
                            valueText = primary,
                            rollover = existing?.rollover ?: false,
                            zoneValues = normalized,
                            billingPeriod = period.toString()
                        ))
                    }, enabled = !recognizing, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
private fun Tools11(data: List<Address>, back: () -> Unit, backup: () -> Unit, restore: () -> Unit, csv: () -> Unit, reminders: () -> Unit, legacyTools: () -> Unit) {
    val context = LocalContext.current
    Frame11 {
        Back11("Мои счётчики", back)
        Text("Данные и передача", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Локально, без аккаунта и сервера", color = M11, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = backup, modifier = Modifier.fillMaxWidth()) { Text("Создать резервную копию") }
        Spacer(Modifier.height(7.dp)); OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Восстановить из копии") }
        Spacer(Modifier.height(7.dp)); OutlinedButton(onClick = csv, modifier = Modifier.fillMaxWidth()) { Text("Экспорт истории CSV") }
        Spacer(Modifier.height(7.dp)); OutlinedButton(onClick = reminders, modifier = Modifier.fillMaxWidth()) { Text("Напоминания и поверка") }
        Spacer(Modifier.height(7.dp)); TextButton(onClick = legacyTools, modifier = Modifier.fillMaxWidth()) { Text("Расширенные функции предыдущих версий") }
        Spacer(Modifier.height(14.dp))
        Text("Текст для передачи", fontWeight = FontWeight.Bold)
        Text("Автоматической отправки в УК/РСО нет.", color = M11, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(data, key = { it.id }) { address ->
                val text = MeterTransfer.transmissionText(address)
                Surface(Modifier.fillMaxWidth().padding(bottom = 7.dp), shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Column(Modifier.padding(12.dp)) {
                        Text(address.name, fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Показания", text))
                            }) { Text("Скопировать") }
                            TextButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Передать показания"))
                            }) { Text("Поделиться") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextDialog11(title: String, label: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { confirm(text.trim()) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

private fun point11(reading: Reading): ZoneReadingPoint = ZoneReadingPoint(
    reading.timestamp,
    if (reading.zoneValues.isNotEmpty()) reading.zoneValues else mapOf("TOTAL" to (reading.valueText ?: reading.value.toString()))
)

private fun displayZones11(meter: Meter, reading: Reading): String {
    val values = point11(reading).values
    val text = MeterZones.normalize(meter.tariffZones).mapNotNull { zone -> values[zone]?.let { if (zone == "TOTAL") it else "$zone $it" } }.joinToString(" · ")
    return "${text.ifBlank { reading.valueText ?: reading.value.toString() }} ${meter.unit}"
}

private fun sanitize11(raw: String): String {
    val normalized = raw.replace(',', '.')
    val out = StringBuilder()
    var separator = false
    normalized.forEach { char ->
        when {
            char.isDigit() -> out.append(char)
            char == '.' && !separator -> { if (out.isEmpty()) out.append('0'); out.append('.'); separator = true }
        }
    }
    return out.toString().take(18)
}

private fun InputStream.readBytesLimited11(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Резервная копия слишком большая" }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun num11(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
private fun date11(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun zoneLabel11(zone: String): String = if (zone == "TOTAL") "Всего" else zone
private fun monthLabel11(month: YearMonth): String = month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
