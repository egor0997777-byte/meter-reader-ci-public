package ru.egor.meters

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val A13 = Color(0xFF6B5BC7)
private val BG13 = Color(0xFFF7F7FA)
private val M13 = Color(0xFF77737F)
private val Scheme13 = lightColorScheme(primary = A13, background = BG13, surface = Color.White)
private const val SECURITY_PREFS = "security_settings"
private const val DEVICE_LOCK_KEY = "device_lock"

class V013MainActivity : ComponentActivity() {
    private val locked = mutableStateOf(false)

    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        locked.value = result.resultCode != Activity.RESULT_OK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
        locked.value = prefs.getBoolean(DEVICE_LOCK_KEY, false)

        setContent {
            MaterialTheme(colorScheme = Scheme13) {
                if (locked.value) {
                    Locked13(onUnlock = ::requestUnlock)
                } else {
                    Hub13(
                        openMeters = { startActivity(Intent(this, V011MainActivity::class.java)) },
                        openTariffs = { startActivity(Intent(this, V012MainActivity::class.java)) },
                        openReminders = { startActivity(Intent(this, V010MainActivity::class.java)) },
                        openData = { startActivity(Intent(this, V07MainActivity::class.java)) },
                        preferences = prefs,
                        lockNow = {
                            if (prefs.getBoolean(DEVICE_LOCK_KEY, false)) {
                                locked.value = true
                                requestUnlock()
                            }
                        }
                    )
                }
            }
        }

        if (locked.value) requestUnlock()
    }

    private fun requestUnlock() {
        val prefs = getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DEVICE_LOCK_KEY, false)) {
            locked.value = false
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            prefs.edit().putBoolean(DEVICE_LOCK_KEY, false).apply()
            locked.value = false
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Разблокировать «Мои счётчики»",
            "Подтвердите PIN, пароль или графический ключ устройства"
        )
        if (intent == null) locked.value = false else unlockLauncher.launch(intent)
    }
}

@Composable
private fun Hub13(
    openMeters: () -> Unit,
    openTariffs: () -> Unit,
    openReminders: () -> Unit,
    openData: () -> Unit,
    preferences: android.content.SharedPreferences,
    lockNow: () -> Unit
) {
    var privacy by remember { mutableStateOf(false) }
    if (privacy) {
        Privacy13(
            preferences = preferences,
            back = { privacy = false },
            lockNow = lockNow
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Мои счётчики", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Всё хранится на устройстве · без аккаунта", color = M13, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
        }
        item { HubCard13("Учёт показаний", "Адреса, счётчики, история, фото и замена счётчика", openMeters) }
        item { HubCard13("Тарифы и стоимость", "Необязательные тарифы и ориентировочный расчёт", openTariffs) }
        item { HubCard13("Напоминания и поверка", "Локальные уведомления и сроки поверки", openReminders) }
        item { HubCard13("Данные и передача", "Резервная копия, CSV, копирование и системный Share", openData) }
        item { HubCard13("Безопасность и конфиденциальность", "Локальное хранение, разрешения и блокировка входа", { privacy = true }) }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Column(Modifier.padding(14.dp)) {
                    Text("Offline-first", fontWeight = FontWeight.Bold)
                    Text("Интернет для учёта показаний не нужен. Облака, регистрации и автоматической отправки в УК нет.", color = M13, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Версия 1.0", color = M13, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HubCard13(title: String, body: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(body, color = M13, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Privacy13(
    preferences: android.content.SharedPreferences,
    back: () -> Unit,
    lockNow: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    val deviceSecure = remember { keyguard.isDeviceSecure }
    var lockEnabled by remember { mutableStateOf(preferences.getBoolean(DEVICE_LOCK_KEY, false) && deviceSecure) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("‹  Главная") }
            Text("Безопасность и конфиденциальность", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Приложение работает как локальный инструмент.", color = M13, fontSize = 13.sp)
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Column(Modifier.padding(14.dp)) {
                    Text("Где находятся данные", fontWeight = FontWeight.Bold)
                    Text("Адреса, показания, тарифы и настройки хранятся в локальной базе приложения. Фото счётчиков сохраняются в каталоге приложения и не зависят от внешней галереи.", color = M13, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Резервные копии и CSV создаются только по вашему действию и сохраняются в выбранное вами место через системный диалог Android.", color = M13, fontSize = 13.sp)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Column(Modifier.padding(14.dp)) {
                    Text("Сеть и разрешения", fontWeight = FontWeight.Bold)
                    Text("Интернет-разрешение не запрашивается. Уведомления используются только для локальных напоминаний и могут быть отключены.", color = M13, fontSize = 13.sp)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Защищать вход блокировкой устройства", fontWeight = FontWeight.Bold)
                            Text(
                                if (deviceSecure) "Используется системный PIN, пароль или графический ключ. Приложение не хранит ваш код."
                                else "Сначала настройте блокировку экрана в Android.",
                                color = M13,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = lockEnabled,
                            enabled = deviceSecure,
                            onCheckedChange = { enabled ->
                                lockEnabled = enabled
                                preferences.edit().putBoolean(DEVICE_LOCK_KEY, enabled).apply()
                            }
                        )
                    }
                    if (lockEnabled) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = lockNow, modifier = Modifier.fillMaxWidth()) { Text("Проверить блокировку сейчас") }
                    }
                }
            }
        }
        item {
            Text(
                "Приложение не передаёт показания в УК/РСО автоматически и не использует скрытые сетевые функции.",
                color = M13,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun Locked13(onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Мои счётчики заблокированы", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Подтвердите блокировку устройства, чтобы открыть данные.", color = M13, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Разблокировать") }
            }
        }
    }
}
