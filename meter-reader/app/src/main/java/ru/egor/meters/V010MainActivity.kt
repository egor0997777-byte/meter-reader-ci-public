package ru.egor.meters

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val Accent010 = Color(0xFF6B5BC7)
private val Bg010 = Color(0xFFF7F7FA)
private val Muted010 = Color(0xFF77737F)
private val Danger010 = Color(0xFFC33E49)
private val Scheme010 = lightColorScheme(primary = Accent010, background = Bg010, surface = Color.White, error = Danger010)
private val DateFormat010 = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale("ru"))

class V010MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MeterRepository(this)
        setContent {
            MaterialTheme(colorScheme = Scheme010) {
                ReminderHub010(repo) { startActivity(Intent(this, V011MainActivity::class.java)) }
            }
        }
    }
}

@Composable
private fun ReminderHub010(repo: MeterRepository, openMeters: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ReminderSettingsStore(context) }
    var data by remember { mutableStateOf(repo.load()) }
    var screen by remember { mutableStateOf("home") }
    var revision by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++
    }

    fun saveMeter(updated: Meter) {
        data = data.map { address ->
            address.copy(meters = address.meters.map { if (it.id == updated.id) updated else it })
        }
        repo.save(data)
        data = repo.load()
        revision++
    }

    Surface(Modifier.fillMaxSize(), color = Bg010) {
        if (screen == "home") {
            HubHome010(
                data = data,
                repo = repo,
                store = store,
                revision = revision,
                openMeters = openMeters,
                openReminders = { screen = "reminders" }
            )
        } else {
            ReminderSettings010(
                data = data,
                repo = repo,
                store = store,
                revision = revision,
                onBack = { screen = "home" },
                requestPermission = {
                    if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onChanged = { revision++ },
                onMeterChanged = ::saveMeter
            )
        }
    }
}

@Composable
private fun HubHome010(
    data: List<Address>,
    repo: MeterRepository,
    store: ReminderSettingsStore,
    revision: Int,
    openMeters: () -> Unit,
    openReminders: () -> Unit
) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val dueAddresses = data.count { address ->
        val settings = store.getAddress(address.id)
        ReminderPolicy.transferStatus(today, TransferWindow(settings.startDay, settings.endDay, settings.enabled)) == TransferStatus.DUE &&
            SubmissionWorkflow.statusForAddress(repo, address, month) != SubmissionStatus.COMPLETE
    }
    val verificationAttention = data.sumOf { address ->
        address.meters.count { meter ->
            meter.status != "closed" && ReminderPolicy.verificationStatus(meter.verificationUntil, today) in setOf(VerificationStatus.SOON, VerificationStatus.EXPIRED)
        }
    }
    val ignored = revision

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)
    ) {
        Text("Мои счётчики", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Локально, без аккаунта и сервера", color = Muted010, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(Modifier.padding(18.dp)) {
                Text("Сегодня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (dueAddresses == 0) "Передача показаний: всё спокойно" else "Пора передать: $dueAddresses", color = if (dueAddresses == 0) Muted010 else Accent010)
                Text(if (verificationAttention == 0) "Поверка: срочных сроков нет" else "Поверка требует внимания: $verificationAttention", color = if (verificationAttention == 0) Muted010 else Danger010)
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(openMeters, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Открыть счётчики", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(openReminders, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Напоминания и поверка", fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("Адресов: ${data.size} · счётчиков: ${data.sumOf { it.meters.count { m -> m.status != "closed" } }}", color = Muted010, fontSize = 12.sp)
    }
}

@Composable
private fun ReminderSettings010(
    data: List<Address>,
    repo: MeterRepository,
    store: ReminderSettingsStore,
    revision: Int,
    onBack: () -> Unit,
    requestPermission: () -> Unit,
    onChanged: () -> Unit,
    onMeterChanged: (Meter) -> Unit
) {
    val context = LocalContext.current
    var addressDialog by remember { mutableStateOf<Address?>(null) }
    var meterDialog by remember { mutableStateOf<Meter?>(null) }
    val notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val globallyEnabled = store.notificationsEnabled()
    val ignored = revision
    val month = YearMonth.now()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹  Главная") }
        Text("Напоминания и поверка", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Проверка выполняется локально раз в день. Интернет не нужен.", color = Muted010, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Уведомления", fontWeight = FontWeight.SemiBold)
                        Text(if (globallyEnabled) "Включены в приложении" else "Отключены в приложении", color = Muted010, fontSize = 12.sp)
                    }
                    Switch(checked = globallyEnabled, onCheckedChange = { store.setNotificationsEnabled(it); onChanged() })
                }
                if (!notificationsGranted && globallyEnabled && Build.VERSION.SDK_INT >= 33) {
                    Spacer(Modifier.height(8.dp))
                    Text("Android ещё не разрешил показывать уведомления.", color = Danger010, fontSize = 12.sp)
                    TextButton(onClick = requestPermission, contentPadding = PaddingValues(0.dp)) { Text("Разрешить уведомления") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (data.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Сначала добавьте адрес и счётчик.", color = Muted010)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(data, key = { it.id }) { address ->
                    val settings = store.getAddress(address.id)
                    val transferWindowStatus = ReminderPolicy.transferStatus(LocalDate.now(), TransferWindow(settings.startDay, settings.endDay, settings.enabled))
                    val submissionStatus = SubmissionWorkflow.statusForAddress(repo, address, month)
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(address.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (!settings.enabled) "Напоминание отключено" else "Передача ${settings.startDay}–${settings.endDay} числа · ${transferLabel010(transferWindowStatus, submissionStatus)}",
                                        color = Muted010,
                                        fontSize = 12.sp
                                    )
                                }
                                TextButton(onClick = { addressDialog = address }) { Text("Настроить") }
                            }
                            if (settings.enabled && transferWindowStatus == TransferStatus.DUE) {
                                Text(
                                    when (submissionStatus) {
                                        SubmissionStatus.COMPLETE -> "Передача подтверждена в месячной сводке."
                                        SubmissionStatus.PARTIAL -> "Передана только часть показаний. Завершите передачу в месячной сводке."
                                        SubmissionStatus.NONE -> "Показания ещё не отмечены как переданные. Отметка выполняется в месячной сводке."
                                    },
                                    fontSize = 12.sp,
                                    color = if (submissionStatus == SubmissionStatus.COMPLETE) Muted010 else Accent010
                                )
                            }
                            address.meters.filter { it.status != "closed" }.forEach { meter ->
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(meter.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(verificationSummary010(meter, store), color = verificationColor010(meter), fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { meterDialog = meter }) { Text("Поверка") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    addressDialog?.let { address ->
        AddressReminderDialog010(
            address = address,
            initial = store.getAddress(address.id),
            onDismiss = { addressDialog = null },
            onSave = { settings -> store.saveAddress(address.id, settings); addressDialog = null; onChanged() }
        )
    }
    meterDialog?.let { meter ->
        VerificationDialog010(
            meter = meter,
            store = store,
            onDismiss = { meterDialog = null },
            onSave = { last, until ->
                store.setLastVerification(meter.id, last)
                onMeterChanged(meter.copy(verificationUntil = until))
                meterDialog = null
            }
        )
    }
}

@Composable
private fun AddressReminderDialog010(address: Address, initial: AddressReminderSettings, onDismiss: () -> Unit, onSave: (AddressReminderSettings) -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var start by remember { mutableStateOf(initial.startDay.toString()) }
    var end by remember { mutableStateOf(initial.endDay.toString()) }
    val startInt = start.toIntOrNull()?.takeIf { it in 1..31 }
    val endInt = end.toIntOrNull()?.takeIf { it in 1..31 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Передача показаний") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(address.name, color = Muted010, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Напоминать каждый месяц", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(start, { start = it.filter(Char::isDigit).take(2) }, label = { Text("С какого числа") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(end, { end = it.filter(Char::isDigit).take(2) }, label = { Text("По какое число") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Text("Для коротких месяцев 29–31 автоматически сдвигаются на последний день месяца.", color = Muted010, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(enabled = startInt != null && endInt != null, onClick = { onSave(initial.copy(enabled = enabled, startDay = startInt!!, endDay = endInt!!)) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun VerificationDialog010(meter: Meter, store: ReminderSettingsStore, onDismiss: () -> Unit, onSave: (Long?, Long?) -> Unit) {
    var last by remember { mutableStateOf(formatDate010(store.getLastVerification(meter.id))) }
    var until by remember { mutableStateOf(formatDate010(meter.verificationUntil)) }
    val lastMillis = parseDate010(last)
    val untilMillis = parseDate010(until)
    val lastValid = last.isBlank() || lastMillis != null
    val untilValid = until.isBlank() || untilMillis != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поверка счётчика") },
        text = {
            Column {
                Text(meter.name, color = Muted010, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(last, { last = it.take(10) }, label = { Text("Последняя поверка") }, placeholder = { Text("ДД.ММ.ГГГГ") }, singleLine = true, isError = !lastValid)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(until, { until = it.take(10) }, label = { Text("Следующая поверка до") }, placeholder = { Text("ДД.ММ.ГГГГ") }, singleLine = true, isError = !untilValid)
                Spacer(Modifier.height(6.dp))
                Text("За 30 дней до срока статус станет «скоро истекает», после даты — «истекла».", color = Muted010, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(enabled = lastValid && untilValid, onClick = { onSave(lastMillis, untilMillis) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun transferLabel010(status: TransferStatus, submissionStatus: SubmissionStatus): String = when {
    submissionStatus == SubmissionStatus.COMPLETE -> "передано"
    submissionStatus == SubmissionStatus.PARTIAL -> "передано частично"
    status == TransferStatus.DUE -> "пора передать"
    status == TransferStatus.UPCOMING -> "ещё рано"
    status == TransferStatus.PASSED -> "окно прошло"
    else -> "отключено"
}

private fun verificationSummary010(meter: Meter, store: ReminderSettingsStore): String {
    val last = store.getLastVerification(meter.id)?.let { "последняя ${formatDate010(it)}" }
    val until = meter.verificationUntil?.let { "до ${formatDate010(it)}" }
    val status = when (ReminderPolicy.verificationStatus(meter.verificationUntil, LocalDate.now())) {
        VerificationStatus.SOON -> "скоро истекает"
        VerificationStatus.EXPIRED -> "истекла"
        VerificationStatus.OK -> "в порядке"
        VerificationStatus.UNKNOWN -> "срок не указан"
    }
    return listOfNotNull(last, until, status).joinToString(" · ")
}

private fun verificationColor010(meter: Meter): Color = when (ReminderPolicy.verificationStatus(meter.verificationUntil, LocalDate.now())) {
    VerificationStatus.SOON, VerificationStatus.EXPIRED -> Danger010
    else -> Muted010
}

private fun formatDate010(millis: Long?): String = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(DateFormat010)
}.orEmpty()

private fun parseDate010(text: String): Long? {
    if (text.isBlank()) return null
    return try {
        LocalDate.parse(text, DateFormat010).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
