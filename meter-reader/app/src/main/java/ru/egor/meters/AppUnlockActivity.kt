package ru.egor.meters

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val APP_SECURITY_PREFS = "security_settings"
private const val APP_DEVICE_LOCK_KEY = "device_lock"

object AppLockCoordinator : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private var changingConfiguration = false
    private var unlockedForForeground = false
    private var gateLaunching = false

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(APP_SECURITY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(APP_DEVICE_LOCK_KEY, false)

    fun markUnlocked() {
        unlockedForForeground = true
        gateLaunching = false
    }

    fun disable(context: Context) {
        context.getSharedPreferences(APP_SECURITY_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(APP_DEVICE_LOCK_KEY, false).apply()
        markUnlocked()
    }

    private fun ensureGate(activity: Activity) {
        if (activity is AppUnlockActivity || !isEnabled(activity) || unlockedForForeground || gateLaunching) return
        gateLaunching = true
        activity.startActivity(Intent(activity, AppUnlockActivity::class.java))
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfiguration) {
            unlockedForForeground = false
            gateLaunching = false
        }
        changingConfiguration = false
        startedActivities++
        ensureGate(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        ensureGate(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        changingConfiguration = activity.isChangingConfigurations
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is AppUnlockActivity && !unlockedForForeground) gateLaunching = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

class AppUnlockActivity : ComponentActivity() {
    private val errorText = mutableStateOf<String?>(null)

    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLockCoordinator.markUnlocked()
            finish()
        } else {
            errorText.value = "Доступ к данным остаётся заблокирован."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
                        Column(
                            Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Мои счётчики заблокированы", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text("Подтвердите PIN, пароль или графический ключ устройства, чтобы открыть данные.")
                            errorText.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(onClick = ::requestUnlock, modifier = Modifier.fillMaxWidth()) {
                                Text("Разблокировать")
                            }
                        }
                    }
                }
            }
        }

        if (!AppLockCoordinator.isEnabled(this)) {
            AppLockCoordinator.markUnlocked()
            finish()
            return
        }
        if (savedInstanceState == null) requestUnlock()
    }

    private fun requestUnlock() {
        if (!AppLockCoordinator.isEnabled(this)) {
            AppLockCoordinator.markUnlocked()
            finish()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            AppLockCoordinator.disable(this)
            finish()
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Разблокировать «Мои счётчики»",
            "Подтвердите блокировку устройства"
        )
        if (intent == null) {
            errorText.value = "Не удалось открыть системную проверку устройства."
        } else {
            unlockLauncher.launch(intent)
        }
    }

    @Deprecated("Back must not reveal protected data")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}