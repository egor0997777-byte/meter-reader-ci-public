package ru.egor.meters

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val A11Hub = Color(0xFF6B5BC7)
private val BG11Hub = Color(0xFFF7F7FA)
private val M11Hub = Color(0xFF77737F)
private val Scheme11Hub = lightColorScheme(primary = A11Hub, background = BG11Hub, surface = Color.White)

private data class HubSummary11(val addresses: Int, val meters: Int, val completedThisMonth: Int)

private class WalkSession11(context: Context) {
    private val prefs = context.getSharedPreferences("v11_walkthrough", Context.MODE_PRIVATE)
    val addressId: String? get() = prefs.getString("address", null)
    val done: Set<String> get() = prefs.getStringSet("done", emptySet())?.toSet().orEmpty()
    val skipped: Set<String> get() = prefs.getStringSet("skipped", emptySet())?.toSet().orEmpty()
    fun start(addressId: String) { prefs.edit().putString("address", addressId).putStringSet("done", emptySet()).putStringSet("skipped", emptySet()).apply() }
    fun markDone(meterId: String) { prefs.edit().putStringSet("done", done + meterId).apply() }
    fun markSkipped(meterId: String) { prefs.edit().putStringSet("skipped", skipped + meterId).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

class V11MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MeterRepository(this)
        val reminderStore = ReminderSettingsStore(this)
        val session = WalkSession11(this)
        setContent {
            var data by remember { mutableStateOf(repo.load()) }
            var walkAddressId by remember { mutableStateOf(session.addressId) }
            var sessionVersion by remember { mutableIntStateOf(0) }
            fun persist(updated: List<Address>) { repo.save(updated); data = repo.load() }
            fun startWalk(addressId: String) { session.start(addressId); walkAddressId = addressId; sessionVersion++ }
            fun finishWalk() { session.clear(); walkAddressId = null; sessionVersion++ }

            MaterialTheme(colorScheme = Scheme11Hub) {
                val currentAddress = data.firstOrNull { it.id == walkAddressId }
                if (currentAddress != null) {
                    key(sessionVersion, currentAddress.id) {
                        Walkthrough11(
                            address = currentAddress,
                            done = session.done,
                            skipped = session.skipped,
                            saveReading = { meter, values, note, location, rollover ->
                                val normalized = values.mapValues { MeterHistory.normalizeReadingText(it.value)!! }
                                val primary = normalized["TOTAL"] ?: normalized["T1"] ?: normalized.values.first()
                                val reading = Reading(
                                    value = primary.toDouble(),
                                    valueText = primary,
                                    timestamp = System.currentTimeMillis(),
                                    note = note.trim(),
                                    rollover = rollover,
                                    zoneValues = normalized
                                )
                                persist(data.map { address ->
                                    if (address.id != currentAddress.id) address else address.copy(
                                        meters = address.meters.map { existing ->
                                            if (existing.id == meter.id) existing.copy(
                                                location = location.trim(),
                                                readings = existing.readings + reading
                                            ) else existing
                                        }
                                    )
                                })
                                session.markDone(meter.id)
                                sessionVersion++
                            },
                            skip = { meterId -> session.markSkipped(meterId); sessionVersion++ },
                            exit = { walkAddressId = null },
                            finish = ::finishWalk
                        )
                    }
                } else {
                    Hub11(
                        data = data,
                        reminderStore = reminderStore,
                        resumableAddressId = session.addressId,
                        startWalk = ::startWalk,
                        openMeters = { startActivity(Intent(this, V011MainActivity::class.java)) },
                        openTariffs = { startActivity(Intent(this, V012MainActivity::class.java)) },
                        openReminders = { startActivity(Intent(this, V010MainActivity::class.java)) },
                        openData = { startActivity(Intent(this, V07MainActivity::class.java)) },
                        openPrivacy = { startActivity(Intent(this, V013MainActivity::class.java)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Hub11(
    data: List<Address>,
    reminderStore: ReminderSettingsStore,
    resumableAddressId: String?,
    startWalk: (String) -> Unit,
    openMeters: () -> Unit,
    openTariffs: () -> Unit,
    openReminders: () -> Unit,
    openData: () -> Unit,
    openPrivacy: () -> Unit
) {
    val activeMeters = data.flatMap { it.meters }.filter { it.status != "closed" }
    val month = YearMonth.now()
    val completed = activeMeters.count { meter -> meter.readings.any { isMonth11(it.timestamp, month) } }
    val summary = HubSummary11(data.size, activeMeters.size, completed)
    val resume = data.firstOrNull { it.id == resumableAddressId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Мои счётчики", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Снять → проверить → передать", color = M11Hub, fontSize = 13.sp)
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(16.dp)) {
                    Text(periodStatus11(data, reminderStore, month), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("В этом месяце снято ${summary.completedThisMonth} из ${summary.meters}", color = M11Hub, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    if (resume != null) {
                        Button(onClick = { startWalk(resume.id) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                            Text("Продолжить обход · ${resume.name}", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (data.size == 1) {
                        Button(onClick = { startWalk(data.first().id) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                            Text(if (summary.meters == 0) "Добавить счётчик" else "Снять показания", fontWeight = FontWeight.SemiBold)
                        }
                    } else if (data.isEmpty()) {
                        Button(onClick = openMeters, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("Добавить адрес и счётчик") }
                    }
                }
            }
        }
        if (data.size > 1) {
            item { Text("Адреса", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            data.forEach { address ->
                item {
                    val active = address.meters.count { it.status != "closed" }
                    Card(onClick = { if (active > 0) startWalk(address.id) else openMeters() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(address.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (active == 0) "Нет активных счётчиков" else "Снять показания · $active", color = M11Hub, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item { Text("Настройки и история", color = M11Hub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        item { HubCard11("Учёт и история", "Адреса, счётчики, фото, замена и история показаний", openMeters) }
        item { HubCard11("Тарифы и стоимость", "Необязательные тарифы и ориентировочная стоимость", openTariffs) }
        item { HubCard11("Напоминания и поверка", "Локальные напоминания и сроки поверки", openReminders) }
        item { HubCard11("Данные и передача", "Backup, CSV, копирование и системный Share", openData) }
        item { TextButton(onClick = openPrivacy, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) { Text("Безопасность и конфиденциальность") } }
        item { Text("Версия 1.1 · offline-first", color = M11Hub, fontSize = 11.sp) }
    }
}

@Composable
private fun Walkthrough11(
    address: Address,
    done: Set<String>,
    skipped: Set<String>,
    saveReading: (Meter, Map<String, String>, String, String, Boolean) -> Unit,
    skip: (String) -> Unit,
    exit: () -> Unit,
    finish: () -> Unit
) {
    val active = address.meters.filter { it.status != "closed" }
    val processed = done + skipped
    val current = active.firstOrNull { it.id !in processed }
    if (current == null) {
        Summary11(address, done, skipped, finish, exit)
        return
    }
    val zones = MeterZones.normalize(current.tariffZones)
    var values by remember(current.id) { mutableStateOf(zones.associateWith { "" }) }
    var note by remember(current.id) { mutableStateOf("") }
    var location by remember(current.id) { mutableStateOf(current.location) }
    var message by remember(current.id) { mutableStateOf<String?>(null) }
    var allowLower by remember(current.id) { mutableStateOf(false) }

    val previous = current.readings.maxByOrNull { it.timestamp }
    val previousValues = previous?.zoneValues?.takeIf { it.isNotEmpty() } ?: previous?.let { mapOf("TOTAL" to MeterHistory.readingText(it)) }.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = exit, contentPadding = PaddingValues(0.dp)) { Text("← На главный") }
            Text(address.name, color = M11Hub, fontSize = 13.sp)
            Text("${processed.size + 1} из ${active.size} · ${current.name}", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            if (current.location.isNotBlank()) Text(current.location, color = M11Hub, fontSize = 13.sp)
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Прошлое показание", color = M11Hub, fontSize = 12.sp)
                    if (previous == null) Text("Нет", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    else zones.forEach { zone ->
                        val text = previousValues[zone] ?: "—"
                        Text(if (zone == "TOTAL") "$text ${current.unit}" else "$zone · $text ${current.unit}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        zones.forEach { zone ->
            item {
                OutlinedTextField(
                    value = values[zone].orEmpty(),
                    onValueChange = { raw -> values = values + (zone to raw.filter { it.isDigit() || it == ',' || it == '.' }.take(20)) },
                    label = { Text(if (zone == "TOTAL") "Новое показание" else "$zone · новое показание") },
                    supportingText = { Text(liveDelta11(previousValues[zone], values[zone], current.integerDigits ?: 6)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            OutlinedTextField(value = location, onValueChange = { location = it.take(80) }, label = { Text("Где стоит счётчик") }, placeholder = { Text("Ванная, кухня, щиток…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(value = note, onValueChange = { note = it.take(240) }, label = { Text("Заметка к этому месяцу · необязательно") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
        message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        item {
            Button(
                onClick = {
                    val normalized = values.mapValues { MeterHistory.normalizeReadingText(it.value) }
                    if (normalized.values.any { it == null }) { message = "Заполните все показания цифрами"; return@Button }
                    val lowers = zones.any { zone ->
                        val prev = previousValues[zone] ?: return@any false
                        val cur = normalized[zone] ?: return@any false
                        MeterHistory.consumption(prev, cur, current.integerDigits ?: 6, false).lowerThanPrevious
                    }
                    if (lowers && !allowLower) { allowLower = true; message = "Значение меньше прошлого. Проверьте цифры. Если это переполнение механического счётчика, нажмите «Сохранить» ещё раз."; return@Button }
                    saveReading(current, normalized.mapValues { it.value!! }, note, location, lowers)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text(if (allowLower) "Сохранить с подтверждением" else "Сохранить и дальше", fontWeight = FontWeight.SemiBold) }
        }
        item {
            TextButton(onClick = { skip(current.id) }, modifier = Modifier.fillMaxWidth()) { Text("Пропустить в этом месяце") }
        }
        item {
            Text("Фото и расширенное редактирование остаются доступны в разделе «Учёт и история». В обходе фото не обязательно.", color = M11Hub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Summary11(address: Address, done: Set<String>, skipped: Set<String>, finish: () -> Unit, exit: () -> Unit) {
    val active = address.meters.filter { it.status != "closed" }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Обход завершён", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text(address.name, color = M11Hub) }
        active.forEach { meter ->
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(meter.name, fontWeight = FontWeight.SemiBold)
                        when {
                            meter.id in skipped -> Text("Пропущено", color = M11Hub, fontSize = 12.sp)
                            meter.id in done -> {
                                val last = meter.readings.maxByOrNull { it.timestamp }
                                val values = last?.zoneValues.orEmpty()
                                Text(if (values.isEmpty()) last?.let { MeterHistory.readingText(it) }.orEmpty() else values.entries.joinToString(" · ") { (z, v) -> if (z == "TOTAL") v else "$z $v" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                            else -> Text("Не обработано", color = M11Hub, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = finish, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("Готово", fontWeight = FontWeight.SemiBold) }
        }
        item { TextButton(onClick = exit, modifier = Modifier.fillMaxWidth()) { Text("Вернуться позже") } }
        item { Text("Копирование и подтверждение передачи будут объединены в следующий этап roadmap v1.3. Сейчас сводка не меняет историю и не отмечает показания переданными.", color = M11Hub, fontSize = 11.sp) }
    }
}

private fun liveDelta11(previous: String?, current: String?, integerDigits: Int): String {
    if (previous == null || current.isNullOrBlank()) return "Расход появится после ввода"
    val normalized = MeterHistory.normalizeReadingText(current) ?: return "Проверьте формат"
    val result = MeterHistory.consumption(previous, normalized, integerDigits, false)
    return when {
        result.lowerThanPrevious -> "Меньше прошлого значения — нужна проверка"
        result.amount != null -> "Расход: ${MeterHistory.format(result.amount)}"
        else -> "Проверьте значение"
    }
}

private fun isMonth11(timestamp: Long, month: YearMonth): Boolean =
    YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()) == month

private fun periodStatus11(data: List<Address>, store: ReminderSettingsStore, month: YearMonth): String {
    if (data.isEmpty()) return "Добавьте первый адрес"
    val today = LocalDate.now()
    val statuses = data.map { address ->
        val settings = store.getAddress(address.id)
        val active = address.meters.filter { it.status != "closed" }
        val completed = active.count { meter -> meter.readings.any { isMonth11(it.timestamp, month) } }
        when {
            store.isTransferred(address.id, month) -> "Передано"
            active.isNotEmpty() && completed == active.size -> "Снято — осталось передать"
            completed > 0 -> "Частично снято"
            today.dayOfMonth < settings.startDay.coerceAtMost(month.lengthOfMonth()) -> "Ещё рано"
            today.dayOfMonth > settings.endDay.coerceAtMost(month.lengthOfMonth()) -> "Срок передачи прошёл"
            else -> "Пора снять показания"
        }
    }
    return if (statuses.distinct().size == 1) statuses.first() else "Есть адреса, требующие внимания"
}

@Composable
private fun HubCard11(title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = M11Hub, fontSize = 12.sp)
        }
    }
}