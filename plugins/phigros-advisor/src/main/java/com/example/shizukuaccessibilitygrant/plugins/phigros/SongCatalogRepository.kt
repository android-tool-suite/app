package com.example.shizukuaccessibilitygrant.plugins.phigros

import android.content.Context
import java.io.File

internal class SongCatalogRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "phigros-data-studio/catalog.tsv")

    fun load(forceRefresh: Boolean = false): List<SongInfo> {
        var lastError: Throwable? = null
        if (forceRefresh || !cacheFile.exists()) {
            runCatching {
                val text = PhigrosHttp.getText(CATALOG_URL)
                val parsed = parseInfoTable(text)
                require(parsed.size > 100) { "曲库返回内容不完整" }
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(text)
                return parsed
            }.onFailure { lastError = it }
        }
        if (cacheFile.exists()) {
            runCatching { parseInfoTable(cacheFile.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val legacy = context.getSharedPreferences("phigros_advisor", Context.MODE_PRIVATE)
            .getString("difficulty_tsv", "")
            .orEmpty()
        if (legacy.isNotBlank()) {
            parseLegacyDifficulty(legacy).takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw IllegalStateException("无法加载曲库，请检查网络后重试", lastError)
    }

    private fun parseInfoTable(text: String): List<SongInfo> = text.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.trimEnd('\r').split('\t')
            if (parts.size < 9) return@mapNotNull null
            val id = normalizeSongId(parts[0])
            val constants = (8..11).map { parts.getOrNull(it)?.toDoubleOrNull() ?: 0.0 }
            if (id.isBlank() || constants.all { it <= 0.0 }) return@mapNotNull null
            SongInfo(
                id = id,
                title = parts.getOrNull(1).orEmpty().ifBlank { id },
                composer = parts.getOrNull(2).orEmpty(),
                illustrator = parts.getOrNull(3).orEmpty(),
                constants = constants,
            )
        }
        .distinctBy { it.id }
        .toList()

    private fun parseLegacyDifficulty(text: String): List<SongInfo> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('\t')
        if (parts.size < 4) return@mapNotNull null
        val id = normalizeSongId(parts[0])
        SongInfo(id, id, "", "", (1..4).map { parts.getOrNull(it)?.toDoubleOrNull() ?: 0.0 })
    }.toList()

    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/Catrong/phi-plugin/main/resources/info/info.csv"
        const val ARTWORK_BASE_URL =
            "https://raw.githubusercontent.com/Catrong/phi-plugin-ill/main/ill/"

        fun normalizeSongId(value: String): String = value.trim().removeSuffix(".0")
    }
}
