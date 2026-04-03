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
 *
 * 오탐 방지 필터 (3단계):
 *
 *   [Step 1] 후보 등록 / 카운터 누적
 *     - 0.5 미만                     → 노이즈, 후보 초기화
 *     - 0.5 이상 & 같은 활동         → 카운터 누적
 *     - 0.5 이상 & 다른 활동이 연속 2번 → 후보 교체 & 카운터 리셋
 *       (1번짜리 노이즈는 후보 교체 안 함 — 전환 중 Walking 끼어들기 방지)
 *
 *   [Step 2] 카운터 충족 확인
 *     - CONFIRM_COUNT(2)번 미만       → 미확정, 대기
 *
 *   [Step 3] 확정 임계값 확인
 *     - 카운터 누적 중 0.9 이상이 1번이라도 있었으면 → 확정
 *     - 한 번도 0.9를 안 넘었으면    → 대기 (jogging→walking 오탐 0.88 차단)
 *
 * 디버깅:
 *   모든 추론 결과를 CSV에 기록 (confirmed=0: 미확정, confirmed=1: 확정)
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var classifier: ActivityClassifier

    // 슬라이딩 윈도우 버퍼 (최근 250개 유지)
    private val buffer      = ArrayDeque<FloatArray>()
    private val WINDOW_SIZE = 250       // 50Hz × 5초
    private val HOP_SIZE    = 50        // 50Hz × 1초 (1초마다 추론)
    private var hopCounter  = 0

    // 오탐 방지
    //   ACCUMULATE_THRESHOLD : 이 확률 이상이면 카운터 누적 대상 (미만은 노이즈)
    //   CONFIRM_THRESHOLD    : 카운터 누적 중 1번이라도 이 확률을 넘어야 최종 확정
    //   DIFFERENT_THRESHOLD  : 다른 활동이 연속 이 횟수만큼 나와야 후보 교체 (1프레임 노이즈 차단)
    private val ACCUMULATE_THRESHOLD = 0.5f   // 카운터 누적 기준
    private val CONFIRM_THRESHOLD    = 0.9f   // 확정 기준 (jogging→walking 오탐 0.88 차단)
    private val CONFIRM_COUNT        = 2      // 확정에 필요한 연속 횟수
    private val DIFFERENT_THRESHOLD  = 2      // 후보 교체에 필요한 연속 횟수
    private var lastCandidate        = ""     // 현재 후보 활동
    private var consecutiveCount     = 0     // 후보 연속 횟수
    private var differentCount       = 0     // 다른 활동 연속 횟수 (후보 교체 판단용)
    private var hadHighConfidence    = false  // 카운터 누적 중 0.9 이상이 1번이라도 있었는지
    private var confirmedActivity    = ""    // 최종 확정된 활동

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
        Log.d(TAG, "Walking:${"%.3f".format(probs[0])} Jogging:${"%.3f".format(probs[1])} " +
                "Sitting:${"%.3f".format(probs[2])} Standing:${"%.3f".format(probs[3])}")

        // ── 오탐 방지 필터 ────────────────────────────────────

        // Step 1: 후보 등록 / 카운터 누적
        if (maxProb < ACCUMULATE_THRESHOLD) {
            // 0.5 미만 노이즈 → 전부 초기화
            Log.d(TAG, "⚠️ 노이즈 (${maxProb} < $ACCUMULATE_THRESHOLD) → 후보 초기화")
            lastCandidate     = ""
            consecutiveCount  = 0
            differentCount    = 0
            hadHighConfidence = false
            CsvLogger.log(this, activity, confirmedActivity, false, probs, features)
            return

        } else if (activity == lastCandidate) {
            // 같은 활동 → 카운터 누적, differentCount 리셋
            consecutiveCount++
            differentCount = 0
            if (maxProb >= CONFIRM_THRESHOLD) hadHighConfidence = true
            Log.d(TAG, "카운터 누적: $consecutiveCount / $CONFIRM_COUNT " +
                    "(후보: $lastCandidate, 확률: ${"%.3f".format(maxProb)}, highConf: $hadHighConfidence)")

        } else {
            // 다른 활동 → differentCount 누적, DIFFERENT_THRESHOLD 도달 시 후보 교체
            differentCount++
            Log.d(TAG, "다른 활동 감지: $activity (${differentCount}/${DIFFERENT_THRESHOLD}번째, 확률: ${"%.3f".format(maxProb)})")
            if (differentCount >= DIFFERENT_THRESHOLD) {
                Log.d(TAG, "후보 교체: $lastCandidate → $activity")
                lastCandidate     = activity
                consecutiveCount  = differentCount   // 이미 연속으로 본 횟수 반영
                differentCount    = 0
                hadHighConfidence = maxProb >= CONFIRM_THRESHOLD
            }
            CsvLogger.log(this, activity, confirmedActivity, false, probs, features)
            return
        }

        // Step 2: 카운터 미충족 → 아직 미확정
        if (consecutiveCount < CONFIRM_COUNT) {
            Log.d(TAG, "⏳ 아직 미확정 → 이전 활동 유지: $confirmedActivity")
            CsvLogger.log(this, activity, confirmedActivity, false, probs, features)
            return
        }

        // Step 3: 카운터 충족 → 누적 중 0.9 이상이 1번이라도 있었는지 확인
        if (!hadHighConfidence) {
            Log.d(TAG, "⏳ 카운터 충족이나 0.9 이상 추론 없음 → 대기 (오탐 차단)")
            CsvLogger.log(this, activity, confirmedActivity, false, probs, features)
            return
        }

        // ── 활동 확정 ─────────────────────────────────────────
        confirmedActivity = activity
        consecutiveCount  = 0     // 확정 후 카운터 리셋
        hadHighConfidence = false  // 다음 후보를 위해 초기화

        Log.d(TAG, "✅ 활동 확정: $confirmedActivity")

        // 확정 추론 CSV 기록 (confirmed=1)
        CsvLogger.log(this, confirmedActivity, confirmedActivity, true, probs, features)
        Log.d(TAG, "CSV 저장 완료")

        // 알림 업데이트
        updateNotification(confirmedActivity)

        // UI 콜백 전달
        onActivityDetected?.invoke(confirmedActivity, probs)
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