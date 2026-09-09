package ru.egor.meters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

private val A13 = Color(0xFF6B5BC7)
private val BG13 = Color(0xFFF7F7FA)
private val M13 = Color(0xFF77737F)
private val C13 = lightColorScheme(primary = A13, background = BG13, surface = Color.White)

private class WalkSession13(context: Context) {
    private val prefs = context.getSharedPreferences("v13_walkthrough", Context.MODE_PRIVATE)
    val addressId: String? get() = prefs.getString("address", null)
    val period: YearMonth? get() = prefs.getString("period", null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val done: Set<String> get() = prefs.getStringSet("done", emptySet())?.toSet().orEmpty()
    val skipped: Set<String> get() = prefs.getStringSet("skipped", emptySet())?.toSet().orEmpty()
    fun start(addressId: String, period: YearMonth, done: Set<String> = emptySet()) {
        prefs.edit().putString("address", addressId).putString("period", period.toString())
            .putStringSet("done", done).putStringSet("skipped", emptySet()).apply()
    }
    fun markDone(meterId: String) { prefs.edit().putStringSet("done", done + meterId).apply() }
    fun markSkipped(meterId: String) { prefs.edit().putStringSet("skipped", skipped + meterId).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

class V13MainActivity : ComponentActivity() {
    private var refreshFromRoom: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        refreshFromRoom?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MeterRepository(this)
        val reminderStore = ReminderSettingsStore(this)
        val templateStore = TransmissionTemplateStore(this)
        val session = WalkSession13(this)
        setContent {
            var data by remember { mutableStateOf(repo.load()) }
            var walkAddressId by remember { mutableStateOf(session.addressId) }
            var selectedPeriod by remember { mutableStateOf(session.period ?: YearMonth.now()) }
            var version by remember { mutableIntStateOf(0) }
            fun persist(updated: List<Address>) { repo.save(updated); data = repo.load() }
            fun startWalk(addressId: String, period: YearMonth = YearMonth.now()) {
                val address = data.firstOrNull { it.id == addressId }
                val completed = address?.let { MonthlyWalkPlanner.completedMeterIds(it, period) }.orEmpty()
                session.start(addressId, period, completed)
                walkAddressId = addressId
                selectedPeriod = period
                version++
            }
            fun resumeWalk(addressId: String, period: YearMonth) {
                if (session.addressId == addressId && session.period == period) {
                    walkAddressId = addressId; selectedPeriod = period; data = repo.load(); version++
                } else {
                    startWalk(addressId, period)
                }
            }
            fun leaveWalk() { walkAddressId = null; version++ }
            fun finishWalk() { session.clear(); walkAddressId = null; version++ }

            DisposableEffect(Unit) {
                refreshFromRoom = { data = repo.load(); version++ }
                onDispose { refreshFromRoom = null }
            }

            MaterialTheme(colorScheme = C13) {
                val address = data.firstOrNull { it.id == walkAddressId }
                if (address == null) {
                    Hub13(data, repo, reminderStore, templateStore, session.addressId, session.period, ::startWalk, ::resumeWalk,
                        openMeters = { startActivity(Intent(this, V011MainActivity::class.java)) },
                        openTariffs = { startActivity(Intent(this, V012MainActivity::class.java)) },
                        openReminders = { startActivity(Intent(this, V010MainActivity::class.java)) },
                        openData = { startActivity(Intent(this, V011MainActivity::class.java)) },
                        openPrivacy = { startActivity(Intent(this, V013MainActivity::class.java)) })
                } else {
                    key(version, address.id, selectedPeriod.toString()) {
                        Walkthrough13(
                            address = address,
                            period = selectedPeriod,
                            done = session.done,
                            skipped = session.skipped,
                            repo = repo,
                            templateStore = templateStore,
                            saveReading = { meter, values, note, location, rollover ->
                                val normalized = ReadingValidator.normalizeForMeter(values, meter)
                                    ?: error("Показание не соответствует разрядности или тарифным зонам счётчика")
                                val primary = normalized["TOTAL"] ?: normalized["T1"] ?: normalized.values.first()
                                val reading = Reading(
                                    value = primary.toDouble(),
                                    valueText = primary,
                                    timestamp = System.currentTimeMillis(),
                                    note = note.trim(),
                                    rollover = rollover,
                                    zoneValues = normalized,
                                    billingPeriod = selectedPeriod.toString()
                                )
                                persist(data.map { a -> if (a.id != address.id) a else a.copy(meters = a.meters.map { existing ->
                                    if (existing.id == meter.id) existing.copy(location = location.trim(), readings = existing.readings + reading) else existing
                                }) })
                                session.markDone(meter.id); version++
                            },
                            skip = { id -> session.markSkipped(id); version++ },
                            exit = ::leaveWalk,
                            finish = ::finishWalk,
                            refresh = { data = repo.load(); version++ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hub13(
    data: List<Address>, repo: MeterRepository, reminderStore: ReminderSettingsStore,
    templateStore: TransmissionTemplateStore,
    resumableAddressId: String?, resumablePeriod: YearMonth?, startWalk: (String, YearMonth) -> Unit,
    resumeWalk: (String, YearMonth) -> Unit,
    openMeters: () -> Unit, openTariffs: () -> Unit, openReminders: () -> Unit, openData: () -> Unit, openPrivacy: () -> Unit
) {
    val month = YearMonth.now()
    val resume = if (resumablePeriod == null) null else data.firstOrNull {
        it.id == resumableAddressId && it.meters.any { meter -> meter.status != "closed" }
    }
    val progressByAddress = data.associate { it.id to MonthlyWalkPlanner.progress(it, month) }
    val activeCount = progressByAddress.values.sumOf { it.activeCount }
    val completedCount = progressByAddress.values.sumOf { it.completedCount }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Мои счётчики", fontSize = 30.sp, fontWeight = FontWeight.Bold); Text("Снять → проверить → передать", color = M13, fontSize = 13.sp) }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(16.dp)) {
                    Text(periodStatus13(data, repo, reminderStore, templateStore, month), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp)); Text("В этом периоде снято $completedCount из $activeCount", color = M13, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    if (resume != null) {
                        Button(onClick = { resumeWalk(resume.id, resumablePeriod ?: month) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
                            Text("Продолжить обход · ${resume.name}", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        val only = data.singleOrNull()
                        val progress = only?.let { progressByAddress[it.id] }
                        when {
                            only != null && progress != null && progress.activeCount > 0 -> {
                                val submissionStatus = SubmissionStatusPolicy.forTemplates(repo, only, templateStore.list(only), month)
                                val action = when {
                                    progress.remainingCount > 0 -> "Снять показания · осталось ${progress.remainingCount}"
                                    submissionStatus != SubmissionStatus.COMPLETE -> "Проверить и передать"
                                    else -> "Посмотреть сводку"
                                }
                                Button(onClick = { startWalk(only.id, month) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
                                    Text(action, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            data.isEmpty() -> Button(onClick = openMeters, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("Добавить адрес и счётчик") }
                        }
                    }
                }
            }
        }
        if (data.size > 1) {
            item { Text("Адреса", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            data.forEach { address -> item {
                val progress = progressByAddress.getValue(address.id)
                val submissionStatus = if (progress.activeCount == 0) SubmissionStatus.NONE else SubmissionStatusPolicy.forTemplates(repo, address, templateStore.list(address), month)
                val subtitle = when {
                    progress.activeCount == 0 -> "Нет активных счётчиков"
                    submissionStatus == SubmissionStatus.COMPLETE -> "Передано"
                    progress.remainingCount == 0 -> "Снято — осталось передать"
                    progress.completedCount > 0 -> "Осталось ${progress.remainingCount} из ${progress.activeCount}"
                    else -> "Снять показания · ${progress.activeCount}"
                }
                Card(onClick = { if (progress.activeCount > 0) startWalk(address.id, month) else openMeters() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) { Text(address.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = M13, fontSize = 12.sp) }
                }
            } }
        }
        item { Text("Настройки и история", color = M13, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        item { HubCard13("Учёт и история", "Адреса, счётчики, фото, замена и история показаний", openMeters) }
        item { HubCard13("Тарифы и стоимость", "Необязательные тарифы и ориентировочная стоимость", openTariffs) }
        item { HubCard13("Напоминания и поверка", "Локальные напоминания и сроки поверки", openReminders) }
        item { HubCard13("Данные", "Безопасный backup, restore и CSV — затем нажмите «Данные»", openData) }
        item { TextButton(onClick = openPrivacy) { Text("Безопасность и конфиденциальность") } }
        item { Text("Версия 2.0 · offline-first", color = M13, fontSize = 11.sp) }
    }
}

@Composable
private fun Walkthrough13(
    address: Address, period: YearMonth, done: Set<String>, skipped: Set<String>, repo: MeterRepository,
    templateStore: TransmissionTemplateStore,
    saveReading: (Meter, Map<String, String>, String, String, Boolean) -> Unit,
    skip: (String) -> Unit, exit: () -> Unit, finish: () -> Unit, refresh: () -> Unit
) {
    val active = address.meters.filter { it.status != "closed" }
    val processed = done + skipped
    val current = active.firstOrNull { it.id !in processed }
    if (current == null) {
        SubmissionSummary13(address, period, done, skipped, repo, templateStore, finish, exit, refresh)
        return
    }
    val zones = MeterZones.normalize(current.tariffZones)
    var values by remember(current.id) { mutableStateOf(zones.associateWith { "" }) }
    var note by remember(current.id) { mutableStateOf("") }
    var location by remember(current.id) { mutableStateOf(current.location) }
    var message by remember(current.id) { mutableStateOf<String?>(null) }
    var allowLower by remember(current.id) { mutableStateOf(false) }
    val previous = current.readings.filter { readingPeriod13(it) != period }.maxByOrNull { it.timestamp } ?: current.readings.maxByOrNull { it.timestamp }
    val previousValues = previous?.zoneValues?.takeIf { it.isNotEmpty() } ?: previous?.let { mapOf("TOTAL" to MeterHistory.readingText(it)) }.orEmpty()

    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = exit, contentPadding = PaddingValues(0.dp)) { Text("← На главный") }; Text("${address.name} · $period", color = M13, fontSize = 13.sp); Text("${processed.size + 1} из ${active.size} · ${current.name}", fontSize = 25.sp, fontWeight = FontWeight.Bold); if (current.location.isNotBlank()) Text(current.location, color = M13, fontSize = 13.sp) }
        item { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text("Прошлое показание", color = M13, fontSize = 12.sp); if (previous == null) Text("Нет", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) else zones.forEach { zone -> Text(if (zone == "TOTAL") "${previousValues[zone] ?: "—"} ${current.unit}" else "$zone · ${previousValues[zone] ?: "—"} ${current.unit}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) } } } }
        zones.forEach { zone -> item { OutlinedTextField(value = values[zone].orEmpty(), onValueChange = { raw -> values = values + (zone to raw.filter { it.isDigit() || it == ',' || it == '.' }.take(20)) }, label = { Text(if (zone == "TOTAL") "Новое показание" else "$zone · новое показание") }, supportingText = { Text(liveDelta13(previousValues[zone], values[zone], current.integerDigits ?: 6)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth()) } }
        item { OutlinedTextField(location, { location = it.take(80) }, label = { Text("Где стоит счётчик") }, placeholder = { Text("Ванная, кухня, щиток…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(note, { note = it.take(240) }, label = { Text("Заметка к периоду · необязательно") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
        item { Text("Незаписанные цифры не сохраняются. Для безопасного продолжения нажмите «Сохранить и дальше».", color = M13, fontSize = 11.sp) }
        message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        item { Button(onClick = {
            val normalized = ReadingValidator.normalizeForMeter(values, current)
            if (normalized == null) { message = "Проверьте формат, разрядность и все тарифные зоны"; return@Button }
            val integerDigits = ReadingValidator.effectiveDigits(current).integer
            val lowers = zones.any { zone -> val prev = previousValues[zone] ?: return@any false; val cur = normalized[zone] ?: return@any false; MeterHistory.consumption(prev, cur, integerDigits, false).lowerThanPrevious }
            if (lowers && !allowLower) { allowLower = true; message = "Значение меньше прошлого. Проверьте цифры. Если это переполнение, нажмите «Сохранить» ещё раз."; return@Button }
            saveReading(current, normalized, note, location, lowers)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text(if (allowLower) "Сохранить с подтверждением" else "Сохранить и дальше", fontWeight = FontWeight.SemiBold) } }
        item { TextButton(onClick = { skip(current.id) }, modifier = Modifier.fillMaxWidth()) { Text("Пропустить в этом периоде") } }
        item { Text("Фото и расширенное редактирование доступны в «Учёт и история». В обходе фото не обязательно.", color = M13, fontSize = 11.sp) }
    }
}

@Composable
private fun SubmissionSummary13(
    address: Address, period: YearMonth, done: Set<String>, skipped: Set<String>, repo: MeterRepository,
    store: TransmissionTemplateStore, finish: () -> Unit, exit: () -> Unit, refresh: () -> Unit
) {
    val ctx = LocalContext.current
    var templates by remember(address.id) { mutableStateOf(store.list(address)) }
    var editing by remember { mutableStateOf<TransmissionTemplate?>(null) }
    var newTemplate by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val active = address.meters.filter { it.status != "closed" }
    val overall = key(revision, templates) { SubmissionStatusPolicy.forTemplates(repo, address, templates, period) }

    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Показания готовы", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("${address.name} · $period", color = M13); Text(statusText13(overall), color = M13, fontSize = 13.sp) }
        active.forEach { meter -> item { Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(meter.name, fontWeight = FontWeight.SemiBold); when { meter.id in skipped -> Text("Пропущено в этом периоде", color = M13, fontSize = 12.sp); meter.id in done -> { val reading = meter.readings.filter { readingPeriod13(it) == period }.maxByOrNull { it.timestamp }; val values = reading?.zoneValues.orEmpty(); Text(if (values.isEmpty()) reading?.let { MeterHistory.readingText(it) }.orEmpty() else values.entries.joinToString(" · ") { (z,v) -> if (z == "TOTAL") v else "$z $v" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }; else -> Text("Не обработано", color = M13, fontSize = 12.sp) } } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Передача", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = { newTemplate = true }) { Text("+ Шаблон") } } }
        templates.forEach { template -> item {
            val prepared = SubmissionWorkflow.prepare(address, template, period)
            val status = key(revision, template.id) { SubmissionWorkflow.statusForTemplate(repo, address, template, period) }
            val availablePointIds = prepared.items.map { it.meteringPointId }.toSet() - status.submittedPointIds
            val submitPrepared = if (availablePointIds.isEmpty()) {
                prepared.copy(items = emptyList())
            } else {
                SubmissionWorkflow.prepare(address, template.copy(meteringPointIds = availablePointIds), period)
            }
            val isPartial = prepared.missingPointIds.isNotEmpty()
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text(template.name, fontWeight = FontWeight.SemiBold)
                if (template.recipient.isNotBlank()) Text(template.recipient, color = M13, fontSize = 12.sp)
                Text(statusText13(status.status), color = M13, fontSize = 11.sp)
                TextButton(onClick = { editing = template }, contentPadding = PaddingValues(0.dp)) { Text("Изменить") }
                if (isPartial) {
                    Text("Не заполнено точек: ${prepared.missingPointIds.size}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Text("Заполненную часть можно передать отдельно; общий статус останется частичным.", color = M13, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp)); Text(prepared.text.ifBlank { "Нет данных для передачи" }, fontSize = 13.sp)
                Row { TextButton(onClick = { copy13(ctx, prepared.text); message = "Текст скопирован" }, enabled = prepared.items.isNotEmpty()) { Text("Скопировать") }; TextButton(onClick = { share13(ctx, prepared.text) }, enabled = prepared.items.isNotEmpty()) { Text("Поделиться") } }
                Button(onClick = {
                    runCatching { SubmissionWorkflow.createSubmission(address, template, period, submitPrepared) }
                        .onSuccess {
                            repo.recordSubmission(it); revision++; refresh()
                            message = if (isPartial) "Заполненная часть отмечена как переданная" else "Передача отмечена"
                        }
                        .onFailure { message = it.message ?: "Не удалось отметить передачу" }
                }, enabled = submitPrepared.items.isNotEmpty() && status.status != SubmissionStatus.COMPLETE, modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        status.status == SubmissionStatus.COMPLETE -> "Уже передано"
                        isPartial -> "Отметить заполненную часть"
                        else -> "Отметить переданным"
                    })
                }
            } }
        } }
        message?.let { item { Text(it, color = M13, fontSize = 13.sp) } }
        item { Button(onClick = finish, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("Готово", fontWeight = FontWeight.SemiBold) } }
        item { TextButton(onClick = exit, modifier = Modifier.fillMaxWidth()) { Text("Вернуться позже") } }
    }

    if (newTemplate) TemplateDialog13(address, null, dismiss = { newTemplate = false }) { t -> templates = templates + t; store.save(address.id, templates); newTemplate = false }
    editing?.let { old -> TemplateDialog13(address, old, dismiss = { editing = null }) { changed -> templates = templates.map { if (it.id == changed.id) changed else it }; store.save(address.id, templates); editing = null } }
}

@Composable
private fun TemplateDialog13(address: Address, initial: TransmissionTemplate?, dismiss: () -> Unit, save: (TransmissionTemplate) -> Unit) {
    val active = address.meters.filter { it.status != "closed" }
    var name by remember { mutableStateOf(initial?.name ?: "Новый получатель") }
    var recipient by remember { mutableStateOf(initial?.recipient ?: "") }
    var account by remember { mutableStateOf(initial?.account ?: address.account) }
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var suffix by remember { mutableStateOf(initial?.suffix ?: "") }
    var selected by remember { mutableStateOf(initial?.meteringPointIds ?: active.map { it.meteringPointId ?: it.id }.toSet()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (initial == null) "Новый шаблон" else "Шаблон передачи") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }, singleLine = true)
            OutlinedTextField(recipient, { recipient = it.take(120) }, label = { Text("Получатель") }, singleLine = true)
            OutlinedTextField(account, { account = it.take(80) }, label = { Text("Лицевой счёт") }, singleLine = true)
            Text("Какие точки включать", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            active.forEach { meter -> val point = meter.meteringPointId ?: meter.id; Row { Checkbox(checked = point in selected, onCheckedChange = { checked -> selected = if (checked) selected + point else selected - point }); Text(meter.name, modifier = Modifier.padding(top = 12.dp)) } }
            OutlinedTextField(prefix, { prefix = it.take(240) }, label = { Text("Текст перед показаниями") })
            OutlinedTextField(suffix, { suffix = it.take(240) }, label = { Text("Текст после показаний") })
        }
    }, confirmButton = { TextButton(onClick = { if (name.isNotBlank() && selected.isNotEmpty()) save(TransmissionTemplate(id = initial?.id ?: java.util.UUID.randomUUID().toString(), addressId = address.id, name = name.trim(), recipient = recipient.trim(), account = account.trim(), prefix = prefix.trim(), suffix = suffix.trim(), meteringPointIds = selected)) }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

private fun copy13(ctx: Context, text: String) { val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("Показания", text)) }
private fun share13(ctx: Context, text: String) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Передать показания")) }
private fun readingPeriod13(reading: Reading): YearMonth? = BillingPeriodResolver.readingPeriod(reading)
private fun liveDelta13(previous: String?, current: String?, integerDigits: Int): String { if (previous == null || current.isNullOrBlank()) return "Расход появится после ввода"; val normalized = MeterHistory.normalizeReadingText(current) ?: return "Проверьте формат"; val result = MeterHistory.consumption(previous, normalized, integerDigits, false); return when { result.lowerThanPrevious -> "Меньше прошлого значения — нужна проверка"; result.amount != null -> "Расход: ${MeterHistory.format(result.amount)}"; else -> "Проверьте значение" } }
private fun statusText13(status: SubmissionStatus): String = when (status) { SubmissionStatus.NONE -> "Ещё не передано"; SubmissionStatus.PARTIAL -> "Передано частично"; SubmissionStatus.COMPLETE -> "Передано" }
private fun periodStatus13(data: List<Address>, repo: MeterRepository, store: ReminderSettingsStore, templateStore: TransmissionTemplateStore, month: YearMonth): String { if (data.isEmpty()) return "Добавьте первый адрес"; val today = LocalDate.now(); val statuses = data.map { address -> val settings = store.getAddress(address.id); val progress = MonthlyWalkPlanner.progress(address, month); when (SubmissionStatusPolicy.forTemplates(repo, address, templateStore.list(address), month)) { SubmissionStatus.COMPLETE -> "Передано"; SubmissionStatus.PARTIAL -> "Передано частично"; SubmissionStatus.NONE -> when { progress.activeCount == 0 -> "Нет активных счётчиков"; progress.isComplete -> "Снято — осталось передать"; progress.completedCount > 0 -> "Частично снято"; today.dayOfMonth < settings.startDay.coerceAtMost(month.lengthOfMonth()) -> "Ещё рано"; today.dayOfMonth > settings.endDay.coerceAtMost(month.lengthOfMonth()) -> "Срок передачи прошёл"; else -> "Пора снять показания" } } }; return if (statuses.distinct().size == 1) statuses.first() else "Есть адреса, требующие внимания" }

@Composable private fun HubCard13(title: String, body: String, onClick: () -> Unit) { Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(2.dp)); Text(body, color = M13, fontSize = 12.sp) } } }
