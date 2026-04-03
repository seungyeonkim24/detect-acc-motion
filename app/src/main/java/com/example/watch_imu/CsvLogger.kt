package com.example.watch_imu

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 활동 인식 결과를 CSV 파일로 저장합니다.
 *
 * 저장 경로: /sdcard/Android/data/com.example.watch_imu/files/activity_log_yyyyMMdd_HHmmss.csv
 * 수집 시작 시각이 파일명에 포함되어 세션마다 새 파일이 생성됩니다.
 *
 * 컬럼 구성 (총 53개):
 *   timestamp          — 날짜/시각 (문자열)
 *   unix_time          — 밀리초 단위 유닉스 타임
 *   predicted_activity — 추론된 활동명
 *   confirmed          — 확정 여부 (1=확정, 0=미확정)
 *   confirmed_activity — 현재 확정된 활동명 (미확정 추론에서도 이전 확정값 기록)
 *   prob_walking       — Walking 확률
 *   prob_jogging       — Jogging 확률
 *   prob_sitting       — Sitting 확률
 *   prob_standing      — Standing 확률
 *   feat_00~feat_42    — 43개 피처값
 */
object CsvLogger {

    private val rowDateFormat  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // 현재 세션의 파일 (init() 호출 시 결정)
    private var currentFile: File? = null

    // 헤더 (53개 컬럼)
    private val HEADER = buildString {
        append("timestamp,unix_time,predicted_activity,confirmed,confirmed_activity,")
        append("prob_walking,prob_jogging,prob_sitting,prob_standing,")
        append((0..42).joinToString(",") { "feat_${it.toString().padStart(2, '0')}" })
    }

    /**
     * CSV 파일 초기화 (수집 시작 버튼 클릭 시 호출)
     * 호출할 때마다 현재 시각이 붙은 새 파일을 생성합니다.
     */
    fun init(context: Context) {
        val timestamp = fileNameFormat.format(Date())
        val fileName  = "activity_log_${timestamp}.csv"
        val dir       = context.getExternalFilesDir(null) ?: context.filesDir
        currentFile   = File(dir, fileName)
        FileWriter(currentFile!!, false).use { it.write(HEADER + "\n") }
    }

    /**
     * 한 행 기록 — 확정/미확정 모두 기록
     *
     * @param context           Context
     * @param predictedActivity 이번 추론에서 나온 활동명
     * @param confirmedActivity 현재까지 확정된 활동명 (미확정 추론이라도 이전 확정값을 넘김)
     * @param isConfirmed       이번 추론이 확정 추론인지 여부
     * @param probs             FloatArray(4) — Walking, Jogging, Sitting, Standing 순서
     * @param features          FloatArray(43) — 43개 피처값
     */
    fun log(
        context: Context,
        predictedActivity: String,
        confirmedActivity: String,
        isConfirmed: Boolean,
        probs: FloatArray,
        features: FloatArray
    ) {
        val file = currentFile ?: return  // init() 미호출 시 무시

        val now       = System.currentTimeMillis()
        val timestamp = rowDateFormat.format(Date(now))

        val row = buildString {
            append("$timestamp,")
            append("$now,")
            append("$predictedActivity,")
            append("${if (isConfirmed) 1 else 0},")
            append("$confirmedActivity,")
            append(probs.joinToString(",") { "%.4f".format(it) })
            append(",")
            append(features.joinToString(",") { "%.6f".format(it) })
        }

        try {
            FileWriter(file, true).use { it.write(row + "\n") }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 현재 세션의 CSV 파일 경로 반환
     */
    fun getFilePath(context: Context): String = currentFile?.absolutePath ?: "미초기화"
}