package ru.egor.meters

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val A12 = Color(0xFF6B5BC7)
private val BG12 = Color(0xFFF7F7FA)
private val M12 = Color(0xFF77737F)
private val D12 = Color(0xFFC33E49)
private val Scheme12 = lightColorScheme(primary = A12, background = BG12, surface = Color.White, error = D12)
private val Date12 = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale("ru"))

class V012MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MeterRepository(this)
        setContent {
            MaterialTheme(colorScheme = Scheme12) {
                TariffHub12(repo) {
                    startActivity(Intent(this, V011MainActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun TariffHub12(repo: MeterRepository, openMeters: () -> Unit) {
    var data by remember { mutableStateOf(repo.load()) }
    var editing by remember { mutableStateOf<Pair<String, Meter>?>(null) }
    val month = YearMonth.now()

    fun saveMeter(addressId: String, changed: Meter) {
        val updated = data.map { address ->
            if (address.id == addressId) address.copy(meters = address.meters.map { if (it.id == changed.id) changed else it }) else address
        }
        repo.save(updated)
        data = repo.load()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text("Мои счётчики", fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Тарифы и ориентировочная стоимость", color = M12, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = openMeters, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Открыть учёт показаний", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(14.dp)) {
                Text("Как считается", fontWeight = FontWeight.Bold)
                Text(
                    "Стоимость всегда пересчитывается из сохранённых показаний и истории тарифов. Для каждого интервала используется тариф, действовавший на дату следующего показания.",
                    color = M12,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(5.dp))
                Text("Сумма только ориентировочная: льготы, нормативы, общедомовые и региональные схемы не учитываются.", color = M12, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        val meters = data.flatMap { address -> address.meters.filter { it.status != "closed" }.map { address.id to it } }
        if (meters.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Сначала добавьте счётчик. Тарифы необязательны.", color = M12)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(meters, key = { it.second.id }) { (addressId, meter) ->
                    val estimate = ZoneAnalytics.estimateMonthlyCost(
                        readings = meter.readings.map(::point12),
                        month = month,
                        zones = meter.tariffZones,
                        schedule = meter.tariffSchedule,
                        zoneId = ZoneId.systemDefault()
                    )
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(meter.name, fontWeight = FontWeight.SemiBold)
                                    if (meter.serial.isNotBlank()) Text("№ ${meter.serial}", color = M12, fontSize = 11.sp)
                                    Text(zoneSummary12(meter), color = M12, fontSize = 11.sp)
                                }
                                TextButton(onClick = { editing = addressId to meter }) { Text("Тарифы") }
                            }
                            if (meter.tariffSchedule.isEmpty()) {
                                Text("Тарифы не настроены — учёт показаний работает без ограничений.", color = M12, fontSize = 12.sp)
                            } else if (estimate == null) {
                                Text("Ориентировочная стоимость: недостаточно данных или тарифов для полного расчёта.", color = M12, fontSize = 12.sp)
                            } else {
                                Text("Ориентировочно за ${monthLabel12(month)}: ${money12(estimate.total)} ₽", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                estimate.intervals.forEach { interval ->
                                    Text(
                                        "${zoneLabel12(interval.zone)} · ${date12(interval.fromTimestamp)} → ${date12(interval.toTimestamp)}: ${number12(interval.consumption)} × ${interval.priceText} = ${money12(interval.amount)} ₽",
                                        color = M12,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { (addressId, meter) ->
        TariffEditor12(
            meter = meter,
            dismiss = { editing = null },
            confirm = { changed -> saveMeter(addressId, changed); editing = null }
        )
    }
}

@Composable
private fun TariffEditor12(meter: Meter, dismiss: () -> Unit, confirm: (Meter) -> Unit) {
    val zones = MeterZones.normalize(meter.tariffZones)
    var schedule by remember(meter.id) { mutableStateOf(meter.tariffSchedule.sortedWith(compareBy<TariffScheduleEntry> { it.validFrom }.thenBy { it.zone })) }
    var selectedZone by remember(meter.id) { mutableStateOf(zones.first()) }
    var dateText by remember(meter.id) { mutableStateOf(LocalDate.now().format(Date12)) }
    var priceText by remember(meter.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun addEntry() {
        val date = parseDate12(dateText)
        val price = ZoneAnalytics.normalizePriceText(priceText)
        when {
            date == null -> error = "Дата должна быть в формате ДД.ММ.ГГГГ"
            price == null -> error = "Введите неотрицательную цену"
            else -> {
                val validFrom = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val entry = TariffScheduleEntry(selectedZone, validFrom, price)
                schedule = (schedule.filterNot { it.zone.equals(selectedZone, true) && it.validFrom == validFrom } + entry)
                    .sortedWith(compareBy<TariffScheduleEntry> { it.validFrom }.thenBy { it.zone })
                priceText = ""
                error = null
            }
        }
    }

    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("История тарифов", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(meter.name, color = M12, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("Добавить тариф с даты", fontWeight = FontWeight.SemiBold)
                if (zones.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        zones.forEach { zone ->
                            FilterChip(selected = selectedZone == zone, onClick = { selectedZone = zone }, label = { Text(zone) })
                        }
                    }
                }
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it.take(10); error = null },
                    label = { Text("Действует с") },
                    placeholder = { Text("ДД.ММ.ГГГГ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = sanitizePrice12(it); error = null },
                    label = { Text("Цена за ${meter.unit}") },
                    suffix = { Text("₽") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Цена тарифа" }
                )
                error?.let { Text(it, color = D12, fontSize = 12.sp) }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = ::addEntry, modifier = Modifier.fillMaxWidth()) { Text("Добавить в историю") }

                Spacer(Modifier.height(14.dp))
                Text("Сохранённые тарифы", fontWeight = FontWeight.Bold)
                if (schedule.isEmpty()) {
                    Text("Нет тарифов. Это не мешает сохранять показания.", color = M12, fontSize = 12.sp)
                } else {
                    schedule.groupBy { it.zone }.forEach { (zone, entries) ->
                        Text(zoneLabel12(zone), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        entries.sortedByDescending { it.validFrom }.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("с ${date12(entry.validFrom)} · ${entry.priceText} ₽/${meter.unit}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                                TextButton(onClick = { schedule = schedule - entry }) { Text("Удалить", color = D12, fontSize = 11.sp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Изменение тарифа не изменяет показания. Расчётная сумма нигде не сохраняется и каждый раз строится заново из истории.",
                    color = M12,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = { confirm(meter.copy(tariffSchedule = schedule)) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
    }
}

private fun point12(reading: Reading): ZoneReadingPoint = ZoneReadingPoint(
    reading.timestamp,
    if (reading.zoneValues.isNotEmpty()) reading.zoneValues else mapOf("TOTAL" to (reading.valueText ?: reading.value.toString()))
)

private fun parseDate12(value: String): LocalDate? = try {
    LocalDate.parse(value.trim(), Date12)
} catch (_: DateTimeParseException) {
    null
}

private fun sanitizePrice12(raw: String): String {
    val normalized = raw.replace(',', '.')
    val out = StringBuilder()
    var dot = false
    normalized.forEach { char ->
        when {
            char.isDigit() -> out.append(char)
            char == '.' && !dot -> { if (out.isEmpty()) out.append('0'); out.append('.'); dot = true }
        }
    }
    return out.toString().take(12)
}

private fun zoneSummary12(meter: Meter): String = if (MeterZones.normalize(meter.tariffZones) == MeterZones.SINGLE) "Однотарифный" else MeterZones.normalize(meter.tariffZones).joinToString(" / ")
private fun zoneLabel12(zone: String): String = if (zone == "TOTAL") "Общий тариф" else zone
private fun number12(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
private fun money12(value: BigDecimal): String = value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
private fun date12(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(Date12)
private fun monthLabel12(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
