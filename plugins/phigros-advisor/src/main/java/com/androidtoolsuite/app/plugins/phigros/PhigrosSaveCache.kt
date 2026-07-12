package com.androidtoolsuite.app.plugins.phigros

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class PhigrosSaveCache(context: Context) {
    private val directory = File(context.filesDir, "phigros-data-studio/saves").apply { mkdirs() }

    fun save(scope: String, save: PhigrosSave, pushTargets: Map<String, PushTarget>) {
        val records = JSONArray()
        save.records.forEach { records.put(recordToJson(it)) }
        val targets = JSONArray()
        pushTargets.forEach { (identity, target) ->
            targets.put(
                JSONObject()
                    .put("identity", identity)
                    .put("targetAccuracy", target.targetAccuracy ?: JSONObject.NULL)
                    .put("resultingRks", target.resultingRks),
            )
        }
        val root = JSONObject()
            .put("formatVersion", 2)
            .put("profile", profileToJson(save.profile))
            .put("records", records)
            .put("pushTargets", targets)
        atomicWrite(file(scope), root.toString())
    }

    fun load(scope: String?): CachedPhigrosSave? {
        if (scope.isNullOrBlank()) return null
        val source = file(scope)
        if (!source.exists()) return null
        return runCatching {
            val root = JSONObject(source.readText())
            val records = root.getJSONArray("records")
            val save = PhigrosSave(
                profile = profileFromJson(root.getJSONObject("profile")),
                records = List(records.length()) { index -> recordFromJson(records.getJSONObject(index)) },
            )
            val targets = root.optJSONArray("pushTargets") ?: JSONArray()
            val pushTargets = List(targets.length()) { index ->
                val item = targets.getJSONObject(index)
                item.getString("identity") to PushTarget(
                    targetAccuracy = item.optDouble("targetAccuracy").takeIf { !item.isNull("targetAccuracy") },
                    resultingRks = item.optDouble("resultingRks"),
                )
            }.toMap()
            CachedPhigrosSave(save, pushTargets)
        }.getOrNull()
    }

    private fun profileToJson(profile: PlayerProfile) = JSONObject()
        .put("playerId", profile.playerId)
        .put("objectId", profile.objectId)
        .put("avatar", profile.avatar)
        .put("background", profile.background)
        .put("selfIntro", profile.selfIntro)
        .put("challengeModeRank", profile.challengeModeRank)
        .put("money", JSONArray(profile.money))
        .put("officialRks", profile.officialRks)
        .put("saveUpdatedAt", profile.saveUpdatedAt)
        .put("gameVersion", profile.gameVersion)
        .put("cleared", JSONArray(profile.cleared))
        .put("fullCombo", JSONArray(profile.fullCombo))
        .put("phi", JSONArray(profile.phi))

    private fun profileFromJson(json: JSONObject) = PlayerProfile(
        playerId = json.optString("playerId", "Phigros Player"),
        objectId = json.optString("objectId"),
        avatar = json.optString("avatar"),
        background = json.optString("background"),
        selfIntro = json.optString("selfIntro"),
        challengeModeRank = json.optInt("challengeModeRank"),
        money = longList(json.optJSONArray("money"), 5),
        officialRks = json.optDouble("officialRks"),
        saveUpdatedAt = json.optString("saveUpdatedAt"),
        gameVersion = json.optInt("gameVersion"),
        cleared = intList(json.optJSONArray("cleared"), 4),
        fullCombo = intList(json.optJSONArray("fullCombo"), 4),
        phi = intList(json.optJSONArray("phi"), 4),
    )

    private fun recordToJson(record: ChartRecord) = JSONObject()
        .put("id", record.id)
        .put("title", record.title)
        .put("level", record.level)
        .put("levelIndex", record.levelIndex)
        .put("constant", record.constant)
        .put("score", record.score)
        .put("accuracy", record.accuracy)
        .put("fc", record.fc)
        .put("illustrationUrl", record.illustrationUrl)

    private fun recordFromJson(json: JSONObject) = ChartRecord(
        id = json.getString("id"),
        title = json.optString("title", json.getString("id")),
        level = json.getString("level"),
        levelIndex = json.optInt("levelIndex"),
        constant = json.optDouble("constant"),
        score = json.optInt("score"),
        accuracy = json.optDouble("accuracy"),
        fc = json.optBoolean("fc"),
        illustrationUrl = json.optString("illustrationUrl"),
    )

    private fun longList(array: JSONArray?, size: Int): List<Long> =
        List(size) { index -> array?.optLong(index, 0L) ?: 0L }

    private fun intList(array: JSONArray?, size: Int): List<Int> =
        List(size) { index -> array?.optInt(index, 0) ?: 0 }

    private fun file(scope: String) = File(directory, "save-${safeScope(scope)}.json")

    private fun safeScope(scope: String): String =
        scope.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun atomicWrite(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
    }
}

internal data class CachedPhigrosSave(
    val save: PhigrosSave,
    val pushTargets: Map<String, PushTarget>,
)
