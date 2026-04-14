package com.example.watch_imu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    // UI 상태
    private var isRunning      = mutableStateOf(false)
    private var elapsedSeconds = mutableStateOf(0)
    private var windowCount    = mutableStateOf(0)

    // 경과 시간 타이머
    private var timerThread: Thread? = null

    // Wake Lock
    private var wakeLock: PowerManager.WakeLock? = null

    // 런타임 권한 요청
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startSensorService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SensorService 콜백 연결 (피처 43개 수신)
        SensorService.onFeaturesExtracted = { _ ->
            runOnUiThread {
                windowCount.value++
            }
        }

        setContent {
            CollectionApp(
                isRunning      = isRunning.value,
                elapsedSeconds = elapsedSeconds.value,
                windowCount    = windowCount.value,
                onStart        = { checkPermissionsAndStart() },
                onStop         = { stopSensorService() }
            )
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startSensorService()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun startSensorService() {
        val intent = Intent(this, SensorService::class.java)
        startForegroundService(intent)

        isRunning.value      = true
        elapsedSeconds.value = 0
        windowCount.value    = 0

        // Wake Lock 획득
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "watch_imu:sensor_collection"
        ).also { it.acquire() }

        // 경과 시간 타이머
        timerThread = Thread {
            while (isRunning.value) {
                Thread.sleep(1000)
                runOnUiThread { elapsedSeconds.value++ }
            }
        }.also { it.start() }
    }

    private fun stopSensorService() {
        val intent = Intent(this, SensorService::class.java)
        stopService(intent)

        isRunning.value = false
        timerThread     = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        SensorService.onFeaturesExtracted = null
    }
}

// ── UI ────────────────────────────────────────────────────────

@Composable
fun CollectionApp(
    isRunning: Boolean,
    elapsedSeconds: Int,
    windowCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!isRunning) {
            // ── 대기 화면 ──────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text      = "IMU 수집",
                    fontSize  = 18.sp,
                    color     = Color.White,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick  = onStart,
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(
                        text     = "수집 시작",
                        fontSize = 14.sp,
                        color    = Color.White
                    )
                }
            }

        } else {
            // ── 수집 중 화면 ────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text      = "⏳",
                    fontSize  = 30.sp,
                    textAlign = TextAlign.Center,
                    color     = Color.White
                )

                Text(
                    text      = "수집 중",
                    fontSize  = 18.sp,
                    color     = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                InfoRow(label = "경과",   value = formatTime(elapsedSeconds))
                InfoRow(label = "윈도우", value = "${windowCount}번째")

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick  = onStop,
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFE53935)
                    ),
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(
                        text     = "수집 중단",
                        fontSize = 14.sp,
                        color    = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFFAAAAAA))
        Text(text = value, fontSize = 14.sp, color = Color.White)
    }
}

fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}