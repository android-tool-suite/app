package com.androidtoolsuite.app.plugins.phigros

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class PhigrosSaveParser(catalog: List<SongInfo>) {
    private val songs = catalog.associateBy { SongCatalogRepository.normalizeSongId(it.id) }

    fun parse(zipBytes: ByteArray, playerInfo: JSONObject, saveInfo: JSONObject): PhigrosSave {
        val entries = unzip(zipBytes)
        val summary = parseSummary(saveInfo.optString("summary"))
        val user = entries["user"]?.let(::decrypt)?.let(::parseUser) ?: UserData()
        val progress = entries["gameProgress"]?.let(::decrypt)?.let(::parseProgress) ?: ProgressData()
        val recordBytes = entries["gameRecord"] ?: throw IOException("存档中缺少 gameRecord")
        val records = parseRecords(decrypt(recordBytes))
        val saveTime = saveInfo.optJSONObject("modifiedAt")?.optString("iso")
            ?: saveInfo.optString("updatedAt", isoNow())
        return PhigrosSave(
            profile = PlayerProfile(
                playerId = playerInfo.optString("nickname", user.selfIntro.ifBlank { "Phigros Player" }),
                objectId = playerInfo.optString("objectId"),
                avatar = user.avatar.ifBlank { summary.avatar },
                background = user.background,
                selfIntro = user.selfIntro,
                challengeModeRank = if (progress.challengeModeRank > 0) progress.challengeModeRank else summary.challengeModeRank,
                money = progress.money,
                officialRks = summary.rankingScore,
                saveUpdatedAt = saveTime,
                gameVersion = summary.gameVersion,
                cleared = summary.cleared,
                fullCombo = summary.fullCombo,
                phi = summary.phi,
            ),
            records = records,
        )
    }

    private fun parseRecords(bytes: ByteArray): List<ChartRecord> {
        val cursor = ByteCursor(bytes)
        val count = cursor.readVarInt()
        val result = ArrayList<ChartRecord>(count * 3)
        val levels = listOf("EZ", "HD", "IN", "AT", "LEGACY")
        while (cursor.remaining > 0 && result.size < count * 5) {
            val rawId = cursor.readString()
            cursor.skipVarInt()
            val present = cursor.readUnsignedByte()
            val fcMask = cursor.readUnsignedByte()
            val normalizedId = SongCatalogRepository.normalizeSongId(rawId)
            val info = songs[normalizedId]
            for (levelIndex in 0..4) {
                if (present and (1 shl levelIndex) == 0) continue
                val score = cursor.readIntLe()
                val accuracy = cursor.readFloatLe().toDouble().coerceIn(0.0, 100.0)
                if (levelIndex >= 4 || score <= 0) continue
                val constant = info?.constantAt(levelIndex) ?: 0.0
                result += ChartRecord(
                    id = normalizedId,
                    title = info?.title ?: normalizedId,
                    level = levels[levelIndex],
                    levelIndex = levelIndex,
                    constant = constant,
                    score = score.coerceIn(0, 1_000_000),
                    accuracy = accuracy,
                    fc = score >= 1_000_000 || (fcMask and (1 shl levelIndex) != 0),
                    illustrationUrl = SongCatalogRepository.ARTWORK_BASE_URL +
                        Uri.encode(normalizedId, ".-_") + ".png",
                )
            }
        }
        return result.distinctBy { it.identity }
    }

    private fun parseUser(bytes: ByteArray): UserData {
        val cursor = ByteCursor(bytes)
        cursor.readUnsignedByte()
        val selfIntro = cursor.readString()
        val avatar = cursor.readString()
        val background = cursor.readString()
        return UserData(selfIntro, avatar, background)
    }

    private fun parseProgress(bytes: ByteArray): ProgressData {
        val cursor = ByteCursor(bytes)
        cursor.readUnsignedByte()
        cursor.readString()
        cursor.readVarInt()
        val challenge = cursor.readShortLe()
        val money = List(5) { cursor.readVarInt().toLong() }
        return ProgressData(challenge, money)
    }

    private fun parseSummary(encoded: String): SummaryData {
        if (encoded.isBlank()) return SummaryData()
        return runCatching {
            val cursor = ByteCursor(Base64.decode(encoded, Base64.DEFAULT))
            cursor.readUnsignedByte()
            val challenge = cursor.readShortLe()
            val rks = cursor.readFloatLe().toDouble()
            val version = cursor.readVarInt()
            val avatar = cursor.readString()
            val cleared = MutableList(4) { 0 }
            val fc = MutableList(4) { 0 }
            val phi = MutableList(4) { 0 }
            repeat(4) { level ->
                cleared[level] = cursor.readShortLe()
                fc[level] = cursor.readShortLe()
                phi[level] = cursor.readShortLe()
            }
            SummaryData(challenge, rks, version, avatar, cleared, fc, phi)
        }.getOrElse { SummaryData() }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in REQUIRED_ENTRIES) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    entries[entry.name] = output.toByteArray()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 1) { "存档分片为空" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(SAVE_KEY, "AES"), IvParameterSpec(SAVE_IV))
        return cipher.doFinal(encrypted, 1, encrypted.size - 1)
    }

    private data class UserData(
        val selfIntro: String = "",
        val avatar: String = "",
        val background: String = "",
    )

    private data class ProgressData(
        val challengeModeRank: Int = 0,
        val money: List<Long> = List(5) { 0L },
    )

    private data class SummaryData(
        val challengeModeRank: Int = 0,
        val rankingScore: Double = 0.0,
        val gameVersion: Int = 0,
        val avatar: String = "",
        val cleared: List<Int> = List(4) { 0 },
        val fullCombo: List<Int> = List(4) { 0 },
        val phi: List<Int> = List(4) { 0 },
    )

    private class ByteCursor(private val bytes: ByteArray) {
        var position: Int = 0
        val remaining: Int get() = bytes.size - position

        fun readUnsignedByte(): Int {
            ensure(1)
            return bytes[position++].toInt() and 0xff
        }

        fun readShortLe(): Int {
            ensure(2)
            val value = (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
            position += 2
            return value
        }

        fun readIntLe(): Int {
            ensure(4)
            val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).int
            position += 4
            return value
        }

        fun readFloatLe(): Float {
            ensure(4)
            val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).float
            position += 4
            return value
        }

        fun readVarInt(): Int {
            val first = readUnsignedByte()
            return if (first > 127) (first and 0x7f) xor (readUnsignedByte() shl 7) else first
        }

        fun skipVarInt() {
            readVarInt()
        }

        fun readString(): String {
            val length = readVarInt()
            ensure(length)
            val value = String(bytes, position, length, Charsets.UTF_8)
            position += length
            return value
        }

        private fun ensure(length: Int) {
            if (position + length > bytes.size) throw IOException("存档数据不完整")
        }
    }

    companion object {
        private val REQUIRED_ENTRIES = setOf("gameRecord", "gameProgress", "user", "settings")
        private val SAVE_KEY = byteArrayOf(
            -24, -106, -102, -46, -91, 64, 37, -101, -105, -111, -112, -117, -120, -26, -65, 3,
            30, 109, 33, -107, 110, -6, -42, -118, 80, -35, 85, -42, 122, -80, -110, 75,
        )
        private val SAVE_IV = byteArrayOf(
            42, 79, -16, -118, -56, 13, 99, 7, 0, 87, -59, -107, 24, -56, 50, 83,
        )

        private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
