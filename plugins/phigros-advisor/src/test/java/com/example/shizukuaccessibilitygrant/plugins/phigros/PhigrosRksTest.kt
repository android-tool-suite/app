package com.example.shizukuaccessibilitygrant.plugins.phigros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhigrosRksTest {
    @Test
    fun chartRksMatchesCommunityFormula() {
        assertEquals(0.0, PhigrosRks.chartRks(54.99, 16.0), 1e-9)
        assertEquals(16.0, PhigrosRks.chartRks(100.0, 16.0), 1e-9)
        assertEquals(4.0, PhigrosRks.chartRks(77.5, 16.0), 1e-9)
    }

    @Test
    fun snapshotUsesPhiThreePlusBestTwentySeven() {
        val records = (1..30).map { index ->
            record(index, constant = 17.0 - index / 100.0, accuracy = if (index <= 4) 100.0 else 99.0)
        }
        val snapshot = PhigrosRks.calculate(records)
        val expected = (snapshot.phi.sumOf { it.rks } + snapshot.best27.sumOf { it.rks }) / 30.0
        assertEquals(3, snapshot.phi.size)
        assertEquals(27, snapshot.best27.size)
        assertEquals(expected, snapshot.overall, 1e-9)
    }

    @Test
    fun pushTargetReachesNextVisibleHundredth() {
        val records = (1..30).map { index -> record(index, 16.5 - index / 100.0, 98.0) }
        val snapshot = PhigrosRks.calculate(records)
        val target = PhigrosRks.pushTarget(snapshot.best27.first(), records, snapshot)
        assertNotNull(target.targetAccuracy)
        val nextThreshold = PhigrosRks.nextDisplayedRksThreshold(snapshot.overall)
        assertEquals(true, target.resultingRks + 1e-7 >= nextThreshold)
    }

    private fun record(index: Int, constant: Double, accuracy: Double) = ChartRecord(
        id = "song-$index",
        title = "Song $index",
        level = "IN",
        levelIndex = 2,
        constant = constant,
        score = (accuracy * 10_000).toInt(),
        accuracy = accuracy,
        fc = accuracy >= 100.0,
        illustrationUrl = "",
    )
}
