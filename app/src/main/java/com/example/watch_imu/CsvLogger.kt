package com.example.watch_imu

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IMU 43개 피처를 CSV 파일로 저장합니다.
 *
 * 저장 경로: /sdcard/Android/data/com.example.watch_imu/files/imu_features_yyyyMMdd_HHmmss.csv
 * 수집 시작 시각이 파일명에 포함되어 세션마다 새 파일이 생성됩니다.
 *
 * 컬럼 구성 (총 45개):
 *   timestamp   — 날짜/시각 (문자열)
 *   unix_time   — 밀리초 단위 유닉스 타임
 *   feat_00~feat_42 — 43개 피처값
 *
 * 변경사항:
 *   predicted_activity, prob_* 컬럼 제거
 *   TFLite 추론 없이 피처만 저장
 */
object CsvLogger {

    private val rowDateFormat  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var currentFile: File? = null

    // 헤더 (45개 컬럼)
    private val HEADER = buildString {
        append("timestamp,unix_time,")
        append((0..42).joinToString(",") { "feat_${it.toString().padStart(2, '0')}" })
    }

    /**
     * CSV 파일 초기화 (수집 시작 버튼 클릭 시 호출)
     * 호출할 때마다 현재 시각이 붙은 새 파일을 생성합니다.
     */
    fun init(context: Context) {
        val timestamp = fileNameFormat.format(Date())
        val fileName  = "imu_features_${timestamp}.csv"
        val dir       = context.getExternalFilesDir(null) ?: context.filesDir
        currentFile   = File(dir, fileName)
        FileWriter(currentFile!!, false).use { it.write(HEADER + "\n") }
    }

    /**
     * 한 행 기록
     *
     * @param context  Context
     * @param features FloatArray(43) — 43개 피처값
     */
    fun log(
        context: Context,
        features: FloatArray
    ) {
        val file = currentFile ?: return

        val now       = System.currentTimeMillis()
        val timestamp = rowDateFormat.format(Date(now))

        val row = buildString {
            append("$timestamp,")
            append("$now,")
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