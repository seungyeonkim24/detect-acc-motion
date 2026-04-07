package com.example.watch_imu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 포그라운드 서비스로 실행되는 가속도계 수집 서비스
 * 화면이 꺼지거나 앱을 나가도 데이터 수집이 유지됩니다.
 *
 * 설계:
 *   Sampling Rate : 50Hz (20ms 간격)
 *   Window Size   : 250샘플 (5초)
 *   Hop (스텝)    : 50샘플  (1초마다 추론)
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var classifier: ActivityClassifier

    // 슬라이딩 윈도우 버퍼 (최근 250개 유지)
    private val buffer      = ArrayDeque<FloatArray>()
    private val WINDOW_SIZE = 250       // 50Hz × 5초
    private val HOP_SIZE    = 50        // 50Hz × 1초 (1초마다 추론)
    private var hopCounter  = 0

    private val CHANNEL_ID      = "activity_recognition_channel"
    private val NOTIFICATION_ID = 1
    private val TAG             = "WatchIMU"

    // Hz 측정용
    private var sampleCount    = 0
    private var checkStartTime = 0L

    companion object {
        var onActivityDetected: ((String, FloatArray) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()

        // 1. 포그라운드 서비스 시작
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("감지 중..."))

        // 2. 분류기 초기화
        classifier = ActivityClassifier(this)
        Log.d(TAG, "ActivityClassifier 초기화 완료")

        // 3. CSV 로거 초기화
        CsvLogger.init(this)
        Log.d(TAG, "CsvLogger 초기화 완료 — 저장 경로: ${CsvLogger.getFilePath(this)}")

        // 4. 가속도계 등록 (20ms = 50Hz)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, 20_000)
        Log.d(TAG, "가속도계 등록 완료 (요청 간격: 20ms = 50Hz)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {

        // ── 실제 Hz 측정 ──────────────────────────────────────
        if (sampleCount == 0) checkStartTime = System.currentTimeMillis()
        sampleCount++
        if (sampleCount == WINDOW_SIZE) {
            val elapsed  = System.currentTimeMillis() - checkStartTime
            val actualHz = WINDOW_SIZE * 1000f / elapsed
            Log.d(TAG, "실제 샘플링: ${"%.1f".format(actualHz)}Hz / 소요: ${elapsed}ms")
            sampleCount = 0
        }
        // ──────────────────────────────────────────────────────

        // 슬라이딩 버퍼 유지 (최대 WINDOW_SIZE)
        buffer.addLast(floatArrayOf(event.values[0], event.values[1], event.values[2]))
        if (buffer.size > WINDOW_SIZE) buffer.removeFirst()

        // HOP_SIZE(50샘플 = 1초)마다 추론
        hopCounter++
        if (hopCounter < HOP_SIZE) return
        hopCounter = 0

        // 버퍼가 WINDOW_SIZE만큼 안 찼으면 스킵 (초기 5초 대기)
        if (buffer.size < WINDOW_SIZE) {
            Log.d(TAG, "버퍼 채우는 중: ${buffer.size}/${WINDOW_SIZE}")
            return
        }

        val window = buffer.toTypedArray()

        // 피처 추출 (43개)
        val features = FeatureExtractor.extract(window)

        // TFLite 추론
        val (activity, probs) = classifier.classifyWithProb(features)
        val maxProb = probs.max()

        Log.d(TAG, "─────────────────────────────────────")
        Log.d(TAG, "추론: $activity (확률: ${"%.3f".format(maxProb)})")
        Log.d(TAG, "Walking:${"%.3f".format(probs[0])} " +
                "Sitting:${"%.3f".format(probs[1])} Standing:${"%.3f".format(probs[2])}")

        // CSV 기록
        CsvLogger.log(this, activity, probs, features)
        Log.d(TAG, "CSV 저장 완료")

        // 알림 업데이트
        updateNotification(activity)

        // UI 콜백 전달
        onActivityDetected?.invoke(activity, probs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        classifier.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "SensorService 종료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 알림 관련 ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Activity Recognition",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "활동 인식 실행 중" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(activity: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Activity Recognition")
            .setContentText("현재 활동: $activity")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(activity: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(activity))
    }
}