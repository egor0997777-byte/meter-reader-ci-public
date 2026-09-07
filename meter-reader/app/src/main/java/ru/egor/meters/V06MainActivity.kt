package ru.egor.meters

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Accent06 = Color(0xFF6B5BC7)
private val Bg06 = Color(0xFFF7F7FA)
private val Soft06 = Color(0xFFF1F0F5)
private val Line06 = Color(0xFFE6E4EB)
private val Muted06 = Color(0xFF77737F)
private val Danger06 = Color(0xFFC33E49)

private val MeterColors06 = lightColorScheme(
    primary = Accent06,
    onPrimary = Color.White,
    background = Bg06,
    onBackground = Color(0xFF121216),
    surface = Color.White,
    onSurface = Color(0xFF121216),
    surfaceVariant = Soft06,
    onSurfaceVariant = Muted06,
    outline = Line06,
    error = Danger06
)

class V06MainActivity : ComponentActivity() {
    private var pendingPhotoUri: Uri? = null
    private var pendingPhotoCallback: ((String?) -> Unit)? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhotoUri
        pendingPhotoCallback?.invoke(if (ok && uri != null) uri.toString() else null)
        pendingPhotoUri = null
        pendingPhotoCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = MeterRepository(this)
        setContent {
            MaterialTheme(colorScheme = MeterColors06) {
                MeterApp06(repository) { callback -> launchCamera(callback) }
            }
        }
    }

    private fun launchCamera(callback: (String?) -> Unit) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val file = File(dir, "meter_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhotoUri = uri
        pendingPhotoCallback = callback
        cameraLauncher.launch(uri)
    }
}

private data class Preset06(val title: String, val unit: String, val kind: String, val mark: String)

private val presets06 = listOf(
    Preset06("Холодная вода", "м³", "cold_water", "ХВ"),
    Preset06("Горячая вода", "м³", "hot_water", "ГВ"),
    Preset06("Электричество", "кВт·ч", "electricity", "ЭЭ"),
    Preset06("Газ", "м³", "gas", "Г"),
    Preset06("Отопление", "Гкал", "heating", "Т"),
    Preset06("Другой", "ед.", "other", "•")
)

@Composable
private fun MeterApp06(repository: MeterRepository, onTakePhoto: (((String?) -> Unit) -> Unit)) {
    var addresses by remember { mutableStateOf(repository.load()) }
    var addressId by remember { mutableStateOf<String?>(null) }
    var meterId by remember { mutableStateOf<String?>(null) }

    fun persist(value: List<Address>) {
        addresses = value
        repository.save(value)
    }

    val address = addresses.firstOrNull { it.id == addressId }
    val meter = address?.meters?.firstOrNull { it.id == meterId }

    Surface(Modifier.fillMaxSize(), color = Bg06) {
        when {
            address == null -> Home06(
                addresses,
                onOpen = { addressId = it },
                onAdd = { persist(addresses + Address(name = it)) },
                onDelete = { id -> persist(addresses.filterNot { it.id == id }) }
            )
            meter == null -> Address06(
                address = address,
                onBack = { addressId = null },
                onOpenMeter = { meterId = it },
                onAddMeter = { preset, name, serial ->
                    persist(addresses.map { a ->
                        if (a.id == address.id) a.copy(
                            meters = a.meters + Meter(name = name, unit = preset.unit, kind = preset.kind, serial = serial)
                        ) else a
                    })
                },
                onDeleteMeter = { id ->
                    persist(addresses.map { a ->
                        if (a.id == address.id) a.copy(meters = a.meters.filterNot { it.id == id }) else a
                    })
                }
            )
            else -> Meter06(
                meter = meter,
                onBack = { meterId = null },
                onTakePhoto = onTakePhoto,
                onAddReading = { value, photo, note ->
                    persist(addresses.map { a ->
                        if (a.id != address.id) a else a.copy(meters = a.meters.map { m ->
                            if (m.id == meter.id) m.copy(readings = m.readings + Reading(value = value, photoUri = photo, note = note)) else m
                        })
                    })
                },
                onDeleteReading = { id ->
                    persist(addresses.map { a ->
                        if (a.id != address.id) a else a.copy(meters = a.meters.map { m ->
                            if (m.id == meter.id) m.copy(readings = m.readings.filterNot { it.id == id }) else m
                        })
                    })
                }
            )
        }
    }
}

@Composable
private fun Frame06(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp),
        content = content
    )
}

@Composable
private fun Home06(
    addresses: List<Address>,
    onOpen: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Address?>(null) }
    val meterCount = addresses.sumOf { it.meters.size }
    val readingCount = addresses.sumOf { a -> a.meters.sumOf { it.readings.size } }

    Frame06 {
        Spacer(Modifier.height(4.dp))
        Text("Мои счётчики", fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        Text("Показания, история и фото", color = Muted06, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        if (addresses.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 34.dp)) {
                    Badge06("⌂", 60.dp, 26.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Добавьте первый адрес", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Квартира, дом или дача. Внутри можно добавить любые счётчики.",
                        color = Muted06,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            Summary06(addresses.size, meterCount, readingCount)
            Spacer(Modifier.height(18.dp))
            Text("Адреса", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(addresses, key = { it.id }) { address ->
                    val latest = address.meters.flatMap { it.readings }.maxByOrNull { it.timestamp }
                    Card(
                        onClick = { onOpen(address.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge06("⌂")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(address.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (address.meters.isEmpty()) "Нет счётчиков" else "${address.meters.size} ${pluralMeter06(address.meters.size)}",
                                    color = Muted06,
                                    fontSize = 12.sp
                                )
                                if (latest != null) Text("Обновлено ${date06(latest.timestamp)}", color = Muted06, fontSize = 11.sp)
                            }
                            Overflow06 { deleteTarget = address }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Primary06("Добавить адрес") { showAdd = true }
    }

    if (showAdd) TextEntry06(
        title = "Новый адрес",
        label = "Название или адрес",
        placeholder = "Например, Квартира",
        confirm = "Добавить",
        onDismiss = { showAdd = false },
        onConfirm = { onAdd(it); showAdd = false }
    )

    deleteTarget?.let { target ->
        Confirm06(
            "Удалить адрес?",
            "Все счётчики и показания по адресу «${target.name}» будут удалены.",
            onDismiss = { deleteTarget = null },
            onConfirm = { onDelete(target.id); deleteTarget = null }
        )
    }
}

@Composable
private fun Summary06(addresses: Int, meters: Int, readings: Int) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Soft06) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniStat06(addresses.toString(), "адресов", Modifier.weight(1f))
            VerticalDivider(Modifier.height(24.dp), color = Line06)
            MiniStat06(meters.toString(), "счётчиков", Modifier.weight(1f))
            VerticalDivider(Modifier.height(24.dp), color = Line06)
            MiniStat06(readings.toString(), "записей", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat06(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted06, fontSize = 11.sp)
    }
}

@Composable
private fun Address06(
    address: Address,
    onBack: () -> Unit,
    onOpenMeter: (String) -> Unit,
    onAddMeter: (Preset06, String, String) -> Unit,
    onDeleteMeter: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Meter?>(null) }

    Frame06 {
        Back06("Все адреса", onBack)
        Text(address.name, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
        Text("${address.meters.size} ${pluralMeter06(address.meters.size)}", color = Muted06, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        if (address.meters.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Empty06("Добавьте счётчик", "Выберите тип, название и при необходимости серийный номер.")
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(address.meters, key = { it.id }) { meter ->
                    val sorted = meter.readings.sortedByDescending { it.timestamp }
                    val latest = sorted.firstOrNull()
                    val previous = sorted.getOrNull(1)
                    Card(
                        onClick = { onOpenMeter(meter.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(start = 14.dp, top = 13.dp, bottom = 13.dp, end = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MeterBadge06(meter.kind)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(meter.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    if (meter.serial.isNotBlank()) Text("№ ${meter.serial}", color = Muted06, fontSize = 11.sp)
                                }
                                Overflow06 { deleteTarget = meter }
                            }
                            Spacer(Modifier.height(9.dp))
                            if (latest == null) {
                                Text("Нет показаний", color = Muted06, fontSize = 13.sp)
                                Text("Нажмите, чтобы добавить первое", color = Accent06, fontSize = 12.sp)
                            } else {
                                Text("${value06(latest.value)} ${meter.unit}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(date06(latest.timestamp), color = Muted06, fontSize = 11.sp)
                                    if (previous != null) Text("Расход ${value06(latest.value - previous.value)} ${meter.unit}", color = Muted06, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Primary06("Добавить счётчик") { showAdd = true }
    }

    if (showAdd) AddMeter06(
        onDismiss = { showAdd = false },
        onConfirm = { preset, name, serial -> onAddMeter(preset, name, serial); showAdd = false }
    )

    deleteTarget?.let { meter ->
        Confirm06(
            "Удалить счётчик?",
            "История «${meter.name}» тоже будет удалена.",
            onDismiss = { deleteTarget = null },
            onConfirm = { onDeleteMeter(meter.id); deleteTarget = null }
        )
    }
}

@Composable
private fun Meter06(
    meter: Meter,
    onBack: () -> Unit,
    onTakePhoto: (((String?) -> Unit) -> Unit),
    onAddReading: (Double, String?, String) -> Unit,
    onDeleteReading: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Reading?>(null) }
    val sorted = meter.readings.sortedByDescending { it.timestamp }
    val latest = sorted.firstOrNull()
    val previous = sorted.getOrNull(1)

    Frame06 {
        Back06("Счётчики", onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeterBadge06(meter.kind)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(meter.name, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                if (meter.serial.isNotBlank()) Text("№ ${meter.serial}", color = Muted06, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(Modifier.padding(16.dp)) {
                Text("Последнее показание", color = Muted06, fontSize = 12.sp)
                if (latest == null) {
                    Text("—", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Показаний пока нет", color = Muted06, fontSize = 13.sp)
                } else {
                    Text("${value06(latest.value)} ${meter.unit}", fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
                    Text(dateTime06(latest.timestamp), color = Muted06, fontSize = 12.sp)
                    if (previous != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Line06)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Расход", color = Muted06, fontSize = 12.sp)
                            Text("${value06(latest.value - previous.value)} ${meter.unit}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(17.dp))
        Text("История", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (sorted.isEmpty()) {
            Text("Здесь появятся сохранённые показания.", color = Muted06, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorted, key = { it.id }) { reading ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${value06(reading.value)} ${meter.unit}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(dateTime06(reading.timestamp), color = Muted06, fontSize = 11.sp)
                                if (reading.note.isNotBlank()) Text(reading.note, color = Muted06, fontSize = 11.sp)
                            }
                            if (reading.photoUri != null) Surface(shape = RoundedCornerShape(10.dp), color = Accent06.copy(alpha = .10f)) {
                                Text("Фото", color = Accent06, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            Overflow06 { deleteTarget = reading }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Primary06("Новое показание") { showAdd = true }
    }

    if (showAdd) AddReading06(
        meter = meter,
        previousValue = latest?.value,
        onTakePhoto = onTakePhoto,
        onDismiss = { showAdd = false },
        onConfirm = { value, photo, note -> onAddReading(value, photo, note); showAdd = false }
    )

    deleteTarget?.let { reading ->
        Confirm06(
            "Удалить показание?",
            "Запись от ${date06(reading.timestamp)} будет удалена.",
            onDismiss = { deleteTarget = null },
            onConfirm = { onDeleteReading(reading.id); deleteTarget = null }
        )
    }
}

@Composable
private fun AddMeter06(onDismiss: () -> Unit, onConfirm: (Preset06, String, String) -> Unit) {
    var selected by remember { mutableStateOf(presets06.first()) }
    var name by remember { mutableStateOf(selected.title) }
    var serial by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 680.dp), shape = RoundedCornerShape(26.dp), color = Color.White) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Новый счётчик", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Тип счётчика", color = Muted06, fontSize = 13.sp)
                Spacer(Modifier.height(13.dp))
                presets06.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            val active = preset.kind == selected.kind
                            Surface(
                                onClick = { selected = preset; name = preset.title },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp),
                                color = if (active) Accent06.copy(alpha = .11f) else Soft06,
                                border = if (active) BorderStroke(1.dp, Accent06) else null
                            ) {
                                Column(Modifier.padding(11.dp)) {
                                    Text(preset.mark, color = Accent06, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(Modifier.height(3.dp))
                                    Text(preset.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(preset.unit, color = Muted06, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Field06(
                    value = name,
                    onValueChange = { name = it.take(50) },
                    label = "Название",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
                )
                Spacer(Modifier.height(9.dp))
                Field06(
                    value = serial,
                    onValueChange = { serial = it.take(40) },
                    label = "Серийный номер",
                    placeholder = "Необязательно",
                    supporting = "Можно вводить цифры, русские и английские буквы",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false
                    )
                )
                Actions06(onDismiss, { onConfirm(selected, name.trim(), serial.trim()) }, "Добавить", name.isNotBlank())
            }
        }
    }
}

@Composable
private fun AddReading06(
    meter: Meter,
    previousValue: Double?,
    onTakePhoto: (((String?) -> Unit) -> Unit),
    onDismiss: () -> Unit,
    onConfirm: (Double, String?, String) -> Unit
) {
    val context = LocalContext.current
    var valueText by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var recognizing by remember { mutableStateOf(false) }
    var allowLower by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = valueText.replace(',', '.').toDoubleOrNull()
    val lower = parsed != null && previousValue != null && parsed < previousValue

    Dialog(onDismissRequest = { if (!recognizing) onDismiss() }) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 700.dp), shape = RoundedCornerShape(26.dp), color = Color.White) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Новое показание", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(meter.name, color = Muted06, fontSize = 13.sp)
                Spacer(Modifier.height(13.dp))

                Button(
                    onClick = {
                        if (!recognizing) onTakePhoto { uri ->
                            photoUri = uri
                            if (uri != null) {
                                recognizing = true
                                status = "Распознаю показание…"
                                MeterOcr.recognize(context, uri, previousValue) { result ->
                                    recognizing = false
                                    result.onSuccess { value ->
                                        if (value != null) {
                                            valueText = value06(value)
                                            status = "Распознано. Проверьте цифры перед сохранением."
                                        } else status = "Не удалось найти показание. Введите его вручную."
                                    }.onFailure { status = "Не удалось распознать фото. Введите показание вручную." }
                                }
                            }
                        }
                    },
                    enabled = !recognizing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Soft06, contentColor = Accent06)
                ) {
                    Text(if (photoUri == null) "📷  Сфотографировать счётчик" else "📷  Переснять фото", fontWeight = FontWeight.SemiBold)
                }
                if (recognizing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                status?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Muted06, fontSize = 11.sp) }

                Spacer(Modifier.height(14.dp))
                Text("Показание", color = Muted06, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = sanitize06(it); error = null },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 27.sp, fontWeight = FontWeight.SemiBold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        placeholder = { Text("0") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(meter.unit, color = Muted06, fontSize = 14.sp)
                }
                if (previousValue != null) {
                    Spacer(Modifier.height(5.dp))
                    Text("Предыдущее: ${value06(previousValue)} ${meter.unit}", color = Muted06, fontSize = 11.sp)
                }
                if (parsed != null && previousValue != null && parsed >= previousValue) {
                    Text("Расход: ${value06(parsed - previousValue)} ${meter.unit}", color = Accent06, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                if (lower) {
                    Spacer(Modifier.height(5.dp))
                    Text("Показание меньше предыдущего", color = Danger06, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowLower, onCheckedChange = { allowLower = it })
                        Text("Счётчик заменён", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Field06(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = "Комментарий",
                    placeholder = "Необязательно",
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                error?.let { Spacer(Modifier.height(5.dp)); Text(it, color = Danger06, fontSize = 11.sp) }
                Actions06(
                    onDismiss,
                    {
                        when {
                            parsed == null -> error = "Введите показание"
                            lower && !allowLower -> error = "Подтвердите замену счётчика"
                            else -> onConfirm(parsed, photoUri, note.trim())
                        }
                    },
                    "Сохранить",
                    parsed != null && !recognizing
                )
            }
        }
    }
}

@Composable
private fun TextEntry06(
    title: String,
    label: String,
    placeholder: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = Color.White) {
            Column(Modifier.padding(18.dp)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Field06(
                    value = text,
                    onValueChange = { text = it.take(80) },
                    label = label,
                    placeholder = placeholder,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)
                )
                Actions06(onDismiss, { onConfirm(text.trim()) }, confirm, text.isNotBlank())
            }
        }
    }
}

@Composable
private fun Field06(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    supporting: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        supportingText = supporting?.let { text -> ({ Text(text) }) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent06,
            unfocusedBorderColor = Line06,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
        )
    )
}

@Composable
private fun Actions06(onCancel: () -> Unit, onConfirm: () -> Unit, confirm: String, enabled: Boolean) {
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("Отмена") }
        Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text(confirm) }
    }
}

@Composable
private fun Primary06(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(17.dp)) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Back06(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text("‹  $text", color = Accent06, fontSize = 14.sp)
    }
}

@Composable
private fun Empty06(title: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 30.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text, color = Muted06, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun Badge06(text: String, size: androidx.compose.ui.unit.Dp = 42.dp, font: androidx.compose.ui.unit.TextUnit = 18.sp) {
    Box(Modifier.size(size).background(Accent06.copy(alpha = .10f), RoundedCornerShape(size / 2)), contentAlignment = Alignment.Center) {
        Text(text, color = Accent06, fontSize = font, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MeterBadge06(kind: String) {
    val preset = presets06.firstOrNull { it.kind == kind } ?: presets06.last()
    Badge06(preset.mark, 42.dp, 12.sp)
}

@Composable
private fun Overflow06(onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(8.dp)) {
            Text("•••", color = Muted06, fontSize = 17.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Удалить", color = Danger06) },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}

@Composable
private fun Confirm06(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить", color = Danger06) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White
    )
}

private fun sanitize06(raw: String): String {
    val normalized = raw.replace('.', ',')
    val out = StringBuilder()
    var separator = false
    normalized.forEach { ch ->
        when {
            ch.isDigit() -> out.append(ch)
            ch == ',' && !separator -> {
                if (out.isEmpty()) out.append('0')
                out.append(',')
                separator = true
            }
        }
    }
    return out.toString().take(14)
}

private fun pluralMeter06(count: Int): String {
    val n10 = count % 10
    val n100 = count % 100
    return when {
        n10 == 1 && n100 != 11 -> "счётчик"
        n10 in 2..4 && n100 !in 12..14 -> "счётчика"
        else -> "счётчиков"
    }
}

private fun value06(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(Locale.getDefault(), "%.3f", value).trimEnd('0').trimEnd(',', '.')

private fun date06(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
private fun dateTime06(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
