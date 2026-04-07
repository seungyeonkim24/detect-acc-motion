package com.example.watch_imu

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

/**
 * TFLite 모델을 사용해 활동을 분류합니다.
 *
 * assets에 필요한 파일:
 *   - activity_model.tflite  (Python train.py로 생성)
 *   - scaler_params.json     (Python train.py로 생성)
 *
 * 분류 대상 (3개):
 *   0: Walking
 *   1: Sitting
 *   2: Standing
 */
class ActivityClassifier(context: Context) {

    private val interpreter: Interpreter
    private val mean: FloatArray
    private val scale: FloatArray

    val labels = listOf("Walking", "Sitting", "Standing")

    init {
        // TFLite 모델 로드
        val model = FileUtil.loadMappedFile(context, "activity_model.tflite")
        interpreter = Interpreter(model)

        // StandardScaler 파라미터 로드
        val json = context.assets.open("scaler_params.json")
            .bufferedReader()
            .readText()
        val obj = JSONObject(json)
        val meanArr  = obj.getJSONArray("mean")
        val scaleArr = obj.getJSONArray("scale")

        mean  = FloatArray(43) { meanArr.getDouble(it).toFloat() }
        scale = FloatArray(43) { scaleArr.getDouble(it).toFloat() }
    }

    /**
     * 43개 피처를 받아 활동명을 반환합니다.
     * @param rawFeatures FloatArray(43) — FeatureExtractor.extract() 결과
     * @return 활동명 (예: "Walking")
     */
    fun classify(rawFeatures: FloatArray): String {
        val (label, _) = classifyWithProb(rawFeatures)
        return label
    }

    /**
     * 각 클래스의 확률값도 함께 반환합니다.
     * @return Pair(활동명, FloatArray(3) 확률)
     */
    fun classifyWithProb(rawFeatures: FloatArray): Pair<String, FloatArray> {
        // StandardScaler 정규화 (Python 학습 시와 동일하게 적용)
        val scaled = FloatArray(43) { i ->
            (rawFeatures[i] - mean[i]) / scale[i]
        }

        val input  = Array(1) { scaled }          // [1, 43]
        val output = Array(1) { FloatArray(3) }   // [1, 3]
        interpreter.run(input, output)

        val probs  = output[0]
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        return Pair(labels[maxIdx], probs)
    }

    fun close() {
        interpreter.close()
    }
}