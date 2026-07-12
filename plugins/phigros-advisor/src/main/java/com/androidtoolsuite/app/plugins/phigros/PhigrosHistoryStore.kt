package com.androidtoolsuite.app.plugins.phigros

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

internal class PhigrosHistoryStore(context: Context) {
    private val directory = File(context.filesDir, "phigros-data-studio").apply { mkdirs() }
    fun record(scope: String, save: PhigrosSave, snapshot: RksSnapshot): List<TimelineEvent> {
        val previous = readRecords(scope)
        val previousSnapshot = PhigrosRks.calculate(previous.values.toList())
        val beforePhi = previousSnapshot.phi.mapTo(hashSetOf()) { it.identity }
        val beforeBest = previousSnapshot.best27.mapTo(hashSetOf()) { it.identity }
        val afterPhi = snapshot.phi.mapTo(hashSetOf()) { it.identity }
        val afterBest = snapshot.best27.mapTo(hashSetOf()) { it.identity }

        val changes = save.records.mapNotNull { current ->
            val old = previous[current.identity]
            val improved = old == null || current.score > old.score || current.accuracy > old.accuracy + 1e-5 || (current.fc && !old.fc)
            if (!improved) return@mapNotNull null
            val tag = when {
                current.identity in afterPhi && current.identity !in beforePhi -> "进入 P3"
                current.identity in afterBest && current.identity !in beforeBest -> "进入 B27"
                old == null -> if (previous.isEmpty()) "首次同步" else "新成绩"
                else -> "成绩提升"
            }
            TimelineChartChange(
                id = current.id,
                title = current.title,
                level = current.level,
                oldScore = old?.score,
                newScore = current.score,
                oldAccuracy = old?.accuracy,
                newAccuracy = current.accuracy,
                tag = tag,
            )
        }.sortedByDescending { change ->
            val current = save.records.first { it.id == change.id && it.level == change.level }
            val old = previous[current.identity]
            current.rks - (old?.rks ?: 0.0)
        }

        val timeline = load(scope).toMutableList()
        val oldRks = previousSnapshot.overall.takeIf { previous.isNotEmpty() }
        val saveTimestamp = save.profile.saveUpdatedAt
        val isDuplicate = timeline.firstOrNull()?.let {
            it.saveTimestamp == saveTimestamp && abs(it.newRks - snapshot.overall) < 1e-8 && changes.isEmpty()
        } == true
        if (!isDuplicate && (changes.isNotEmpty() || oldRks == null || abs(snapshot.overall - oldRks) > 1e-8)) {
            timeline.add(
                0,
                TimelineEvent(
                    timestamp = parseTimestamp(saveTimestamp),
                    saveTimestamp = saveTimestamp,
                    oldRks = oldRks,
                    newRks = snapshot.overall,
                    challengeModeRank = save.profile.challengeModeRank,
                    changes = changes,
                ),
            )
        }
        writeRecords(scope, save.records)
        writeTimeline(scope, timeline.take(365))
        return timeline.take(365)
    }

    fun load(scope: String?): List<TimelineEvent> {
        if (scope.isNullOrBlank()) return emptyList()
        val timelineFile = timelineFile(scope)
        if (!timelineFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(timelineFile.readText())
            List(array.length()) { index -> eventFromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun readRecords(scope: String): Map<String, ChartRecord> {
        val stateFile = stateFile(scope)
        if (!stateFile.exists()) return emptyMap()
        return runCatching {
            val array = JSONArray(stateFile.readText())
            List(array.length()) { recordFromJson(array.getJSONObject(it)) }.associateBy { it.identity }
        }.getOrDefault(emptyMap())
    }

    private fun writeRecords(scope: String, records: List<ChartRecord>) {
        val array = JSONArray()
        records.forEach { array.put(recordToJson(it)) }
        atomicWrite(stateFile(scope), array.toString())
    }

    private fun writeTimeline(scope: String, events: List<TimelineEvent>) {
        val array = JSONArray()
        events.forEach { array.put(eventToJson(it)) }
        atomicWrite(timelineFile(scope), array.toString())
    }

    private fun stateFile(scope: String) = File(directory, "last-records-${safeScope(scope)}.json")
    private fun timelineFile(scope: String) = File(directory, "timeline-${safeScope(scope)}.json")
    private fun safeScope(scope: String): String = scope.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun atomicWrite(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
    }

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
        levelIndex = json.optInt("levelIndex", 0),
        constant = json.optDouble("constant", 0.0),
        score = json.optInt("score", 0),
        accuracy = json.optDouble("accuracy", 0.0),
        fc = json.optBoolean("fc", false),
        illustrationUrl = json.optString("illustrationUrl", ""),
    )

    private fun eventToJson(event: TimelineEvent): JSONObject {
        val changes = JSONArray()
        event.changes.forEach { change ->
            changes.put(
                JSONObject()
                    .put("id", change.id)
                    .put("title", change.title)
                    .put("level", change.level)
                    .put("oldScore", change.oldScore ?: JSONObject.NULL)
                    .put("newScore", change.newScore)
                    .put("oldAccuracy", change.oldAccuracy ?: JSONObject.NULL)
                    .put("newAccuracy", change.newAccuracy)
                    .put("tag", change.tag),
            )
        }
        return JSONObject()
            .put("timestamp", event.timestamp)
            .put("saveTimestamp", event.saveTimestamp)
            .put("oldRks", event.oldRks ?: JSONObject.NULL)
            .put("newRks", event.newRks)
            .put("challengeModeRank", event.challengeModeRank)
            .put("changes", changes)
    }

    private fun eventFromJson(json: JSONObject): TimelineEvent {
        val array = json.optJSONArray("changes") ?: JSONArray()
        val changes = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            TimelineChartChange(
                id = item.getString("id"),
                title = item.optString("title", item.getString("id")),
                level = item.getString("level"),
                oldScore = item.optInt("oldScore").takeIf { !item.isNull("oldScore") },
                newScore = item.getInt("newScore"),
                oldAccuracy = item.optDouble("oldAccuracy").takeIf { !item.isNull("oldAccuracy") },
                newAccuracy = item.getDouble("newAccuracy"),
                tag = item.optString("tag", "成绩提升"),
            )
        }
        return TimelineEvent(
            timestamp = json.getLong("timestamp"),
            saveTimestamp = json.optString("saveTimestamp"),
            oldRks = json.optDouble("oldRks").takeIf { !json.isNull("oldRks") },
            newRks = json.getDouble("newRks"),
            challengeModeRank = json.optInt("challengeModeRank", 0),
            changes = changes,
        )
    }

    private fun parseTimestamp(value: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd HH:mm:ss")
        for (pattern in patterns) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.ROOT).parse(value)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return System.currentTimeMillis()
    }
}
