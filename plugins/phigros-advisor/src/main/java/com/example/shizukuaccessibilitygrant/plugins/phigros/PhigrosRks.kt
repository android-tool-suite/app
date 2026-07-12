package com.example.shizukuaccessibilitygrant.plugins.phigros

import kotlin.math.floor
import kotlin.math.sqrt

internal object PhigrosRks {
    private const val PHI_COUNT = 3
    private const val BEST_COUNT = 27
    private const val DIVISOR = 30.0

    fun chartRks(accuracy: Double, constant: Double): Double {
        if (accuracy < 55.0 || constant <= 0.0) return 0.0
        val normalized = (accuracy.coerceAtMost(100.0) - 55.0) / 45.0
        return constant * normalized * normalized
    }

    fun accuracyForRks(targetRks: Double, constant: Double): Double? {
        if (constant <= 0.0 || targetRks < 0.0 || targetRks > constant + 1e-9) return null
        return (45.0 * sqrt(targetRks / constant) + 55.0).coerceIn(55.0, 100.0)
    }

    fun calculate(records: List<ChartRecord>): RksSnapshot {
        val sorted = records
            .asSequence()
            .filter { it.score > 0 && it.constant > 0.0 }
            .sortedByDescending { it.rks }
            .toList()
        val phi = sorted.filter { it.accuracy >= 100.0 - 1e-6 || it.score >= 1_000_000 }.take(PHI_COUNT)
        val best27 = sorted.take(BEST_COUNT)
        val total = phi.sumOf { it.rks } + best27.sumOf { it.rks }
        return RksSnapshot(phi, best27, sorted, total / DIVISOR)
    }

    fun nextDisplayedRksThreshold(overall: Double): Double {
        var delta = floor(overall * 100.0) / 100.0 + 0.005 - overall
        if (delta < 0.0) delta += 0.01
        return overall + delta
    }

    fun pushTarget(record: ChartRecord, allRecords: List<ChartRecord>, baseline: RksSnapshot): PushTarget {
        if (record.accuracy >= 100.0 - 1e-7 || record.constant <= 0.0) {
            return PushTarget(null, baseline.overall)
        }
        val targetOverall = nextDisplayedRksThreshold(baseline.overall)
        val perfectResult = calculate(replace(allRecords, record.withAccuracy(100.0))).overall
        if (perfectResult + 1e-8 < targetOverall) return PushTarget(null, perfectResult)

        var low = record.accuracy
        var high = 100.0
        repeat(42) {
            val mid = (low + high) / 2.0
            val simulated = calculate(replace(allRecords, record.withAccuracy(mid))).overall
            if (simulated >= targetOverall) high = mid else low = mid
        }
        val result = calculate(replace(allRecords, record.withAccuracy(high))).overall
        return PushTarget(high, result)
    }

    fun pushTargets(records: List<ChartRecord>, snapshot: RksSnapshot): Map<String, PushTarget> =
        snapshot.sorted.associate { it.identity to pushTarget(it, records, snapshot) }

    private fun replace(
        source: List<ChartRecord>,
        replacement: ChartRecord,
    ): List<ChartRecord> = source.map { if (it.identity == replacement.identity) replacement else it }
}
