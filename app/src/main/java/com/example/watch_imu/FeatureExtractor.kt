package com.example.watch_imu

/**
 * 43개 피처 추출 (논문 Weiss et al. 2012 / BHI 2016 동일 구조)
 *
 * 피처 구성 (총 43개):
 *   x,y,z 각 축:
 *     - Average          (3개)
 *     - Standard Dev     (3개)
 *     - Avg Abs Diff     (3개)
 *     - Binned Dist ×10  (30개)
 *   전체:
 *     - Resultant Accel  (1개)
 *     - Frequency        (3개)
 *
 * 설계:
 *   Sampling Rate: 50Hz (20ms per sample)
 *   Window Size:   250샘플 (5초)
 */
object FeatureExtractor {

    private const val BINS = 10

    /**
     * @param window (250 × 3) 배열 — 5초 × 50Hz, [x, y, z]
     * @return FloatArray(43)
     */
    fun extract(window: Array<FloatArray>): FloatArray {
        val feats = mutableListOf<Float>()

        for (ax in 0..2) {
            val v = FloatArray(window.size) { window[it][ax] }

            // 1. Average
            feats.add(v.mean())

            // 2. Standard Deviation
            feats.add(v.stdDev())

            // 3. Average Absolute Difference
            feats.add(v.avgAbsDiff())

            // 4. Binned Distribution (10개 구간)
            feats.addAll(v.binnedDist(BINS))
        }

        // 5. Average Resultant Acceleration
        val resultant = window.map { row ->
            Math.sqrt(
                (row[0] * row[0] + row[1] * row[1] + row[2] * row[2]).toDouble()
            ).toFloat()
        }.average().toFloat()
        feats.add(resultant)

        // 6. Frequency (peak interval, ms 단위) — x, y, z 축
        for (ax in 0..2) {
            val v = FloatArray(window.size) { window[it][ax] }
            feats.add(v.peakFreq())
        }

        return feats.toFloatArray()  // size = 43
    }

    // ── 내부 확장 함수 ───────────────────────────────────────

    private fun FloatArray.mean(): Float = sum() / size

    private fun FloatArray.stdDev(): Float {
        val m = mean()
        return Math.sqrt(
            map { (it - m) * (it - m) }.sum().toDouble() / size
        ).toFloat()
    }

    private fun FloatArray.avgAbsDiff(): Float {
        val m = mean()
        return map { Math.abs(it - m) }.sum() / size
    }

    private fun FloatArray.binnedDist(bins: Int): List<Float> {
        val minV = minOrNull() ?: 0f
        val maxV = maxOrNull() ?: 0f
        if (minV == maxV) {
            return MutableList(bins) { 0f }.also { it[0] = 1f }
        }
        val step = (maxV - minV) / bins
        val counts = IntArray(bins)
        forEach { v ->
            val idx = ((v - minV) / step).toInt().coerceIn(0, bins - 1)
            counts[idx]++
        }
        return counts.map { it.toFloat() / size }
    }

    /**
     * 파형의 피크 간 평균 시간(ms)
     * 피크가 3개 미만이면 0 반환
     * 50Hz 기준: 샘플 간격 = 20ms
     */
    private fun FloatArray.peakFreq(): Float {
        val peaks = (1 until size - 1).filter { i ->
            this[i] > this[i - 1] && this[i] > this[i + 1]
        }
        if (peaks.size < 3) return 0f
        val intervals = (1 until peaks.size).map {
            (peaks[it] - peaks[it - 1]) * 20f  // 20ms per sample (50Hz)
        }
        return intervals.average().toFloat()
    }
}