package com.example.shizukuaccessibilitygrant.plugins.phigros

import android.graphics.Bitmap
import java.util.Locale

internal enum class PhigrosServer(val label: String) {
    CN("国服"),
    GLOBAL("国际服"),
}

internal data class SongInfo(
    val id: String,
    val title: String,
    val composer: String,
    val illustrator: String,
    val constants: List<Double>,
) {
    fun constantAt(level: Int): Double = constants.getOrElse(level) { 0.0 }
}

internal data class ChartRecord(
    val id: String,
    val title: String,
    val level: String,
    val levelIndex: Int,
    val constant: Double,
    val score: Int,
    val accuracy: Double,
    val fc: Boolean,
    val illustrationUrl: String,
) {
    val rks: Double get() = PhigrosRks.chartRks(accuracy, constant)
    val identity: String get() = "$id|$level"
    val rating: String
        get() = when {
            score >= 1_000_000 -> "PHI"
            fc -> "FC"
            score >= 960_000 -> "V"
            score >= 920_000 -> "S"
            score >= 880_000 -> "A"
            score >= 820_000 -> "B"
            score >= 700_000 -> "C"
            score > 0 -> "F"
            else -> "NEW"
        }

    fun withAccuracy(value: Double): ChartRecord = copy(
        accuracy = value.coerceIn(0.0, 100.0),
        score = if (value >= 100.0) 1_000_000 else score,
        fc = fc || value >= 100.0,
    )
}

internal data class PlayerProfile(
    val playerId: String = "Phigros Player",
    val objectId: String = "",
    val avatar: String = "",
    val background: String = "",
    val selfIntro: String = "",
    val challengeModeRank: Int = 0,
    val money: List<Long> = List(5) { 0L },
    val officialRks: Double = 0.0,
    val saveUpdatedAt: String = "",
    val gameVersion: Int = 0,
    val cleared: List<Int> = List(4) { 0 },
    val fullCombo: List<Int> = List(4) { 0 },
    val phi: List<Int> = List(4) { 0 },
) {
    val challengeTier: Int get() = challengeModeRank / 100
    val challengeValue: Int get() = challengeModeRank % 100
    val dataText: String
        get() {
            val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
            val parts = money.withIndex().reversed().mapNotNull { (index, value) ->
                if (value <= 0) null else "$value ${units.getOrElse(index) { "?" }}"
            }
            return parts.ifEmpty { listOf("0 KiB") }.joinToString(" · ")
        }
}

internal data class PhigrosSave(
    val profile: PlayerProfile,
    val records: List<ChartRecord>,
)

internal data class RksSnapshot(
    val phi: List<ChartRecord>,
    val best27: List<ChartRecord>,
    val sorted: List<ChartRecord>,
    val overall: Double,
) {
    companion object {
        val EMPTY = RksSnapshot(emptyList(), emptyList(), emptyList(), 0.0)
    }
}

internal data class PushTarget(
    val targetAccuracy: Double?,
    val resultingRks: Double,
) {
    val label: String
        get() = targetAccuracy?.let { String.format(Locale.ROOT, "%.4f%%", it) } ?: "无法推分"
}

internal data class TokenProfile(
    val id: String,
    val label: String,
    val server: PhigrosServer,
    val createdAt: Long,
    val lastUsedAt: Long,
)

internal data class StoredToken(
    val profile: TokenProfile,
    val token: String,
)

internal data class TapLoginRequest(
    val server: PhigrosServer,
    val deviceId: String,
    val deviceCode: String,
    val loginUrl: String,
    val expiresAt: Long,
    val intervalSeconds: Int,
)

internal data class TimelineChartChange(
    val id: String,
    val title: String,
    val level: String,
    val oldScore: Int?,
    val newScore: Int,
    val oldAccuracy: Double?,
    val newAccuracy: Double,
    val tag: String,
)

internal data class TimelineEvent(
    val timestamp: Long,
    val saveTimestamp: String,
    val oldRks: Double?,
    val newRks: Double,
    val challengeModeRank: Int,
    val changes: List<TimelineChartChange>,
) {
    val rksDelta: Double get() = newRks - (oldRks ?: newRks)
}

internal enum class PhigrosPage(val title: String) {
    OVERVIEW("总览"),
    SCORES("RKS 列表"),
    B30("B30"),
    HISTORY("时间线"),
    CATALOG("定数表"),
    TOKENS("令牌"),
}

internal data class GeneratedImage(
    val bitmap: Bitmap,
    val suggestedFileName: String,
    val kind: String,
)

internal data class UiDialog(
    val title: String,
    val message: String,
)

internal data class PhigrosUiState(
    val page: PhigrosPage = PhigrosPage.OVERVIEW,
    val loading: Boolean = false,
    val dialog: UiDialog? = null,
    val tokenProfiles: List<TokenProfile> = emptyList(),
    val selectedTokenId: String? = null,
    val loginRequest: TapLoginRequest? = null,
    val loginQr: Bitmap? = null,
    val loginSecondsLeft: Int = 0,
    val catalog: List<SongInfo> = emptyList(),
    val save: PhigrosSave? = null,
    val snapshot: RksSnapshot = RksSnapshot.EMPTY,
    val pushTargets: Map<String, PushTarget> = emptyMap(),
    val timeline: List<TimelineEvent> = emptyList(),
    val generatedImage: GeneratedImage? = null,
)
