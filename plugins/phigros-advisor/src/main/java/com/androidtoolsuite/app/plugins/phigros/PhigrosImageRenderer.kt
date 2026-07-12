package com.androidtoolsuite.app.plugins.phigros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

internal class PhigrosImageRenderer(private val context: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverDirectory = File(context.cacheDir, "phigros-covers").apply { mkdirs() }

    fun renderB30(
        save: PhigrosSave,
        snapshot: RksSnapshot,
        pushTargets: Map<String, PushTarget>,
    ): GeneratedImage {
        val items = snapshot.phi.mapIndexed { index, record -> "P${index + 1}" to record } +
            snapshot.best27.mapIndexed { index, record -> "B${index + 1}" to record }
        val width = 1440
        val cardHeight = 176
        val rows = ceil(items.size / 2.0).toInt()
        val height = 410 + rows * cardHeight + 130
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, width, height)
        drawHeader(canvas, save.profile, snapshot, "BEST 30 · P3 + B27")

        items.forEachIndexed { index, (rank, record) ->
            val column = index % 2
            val row = index / 2
            val left = 42f + column * 694f
            val top = 360f + row * cardHeight
            drawRecordCard(canvas, RectF(left, top, left + 660f, top + 150f), rank, record, pushTargets[record.identity])
        }
        drawFooter(canvas, width.toFloat(), height - 58f)
        return generated(bitmap, "b30")
    }

    fun renderProfile(save: PhigrosSave, snapshot: RksSnapshot): GeneratedImage {
        val width = 1440
        val height = 1540
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, width, height)
        drawHeader(canvas, save.profile, snapshot, "PLAYER PROFILE")

        glass(canvas, RectF(56f, 390f, 1384f, 620f), 34f)
        text(canvas, "DATA", 90f, 455f, 30f, 0xff80e6ff.toInt(), true)
        text(canvas, ellipsize(save.profile.dataText, 34), 90f, 535f, 50f, Color.WHITE, true)
        text(canvas, "CHALLENGE MODE", 820f, 455f, 30f, 0xff80e6ff.toInt(), true)
        text(canvas, "${save.profile.challengeTier} / ${save.profile.challengeValue}", 820f, 540f, 72f, Color.WHITE, true)

        val levelNames = listOf("EZ", "HD", "IN", "AT")
        glass(canvas, RectF(56f, 650f, 1384f, 1020f), 34f)
        text(canvas, "PLAY STATISTICS", 88f, 720f, 34f, 0xff80e6ff.toInt(), true)
        text(canvas, "CLEAR", 670f, 780f, 25f, 0xffb8c7da.toInt(), true)
        text(canvas, "FC", 930f, 780f, 25f, 0xff8ff0ff.toInt(), true)
        text(canvas, "PHI", 1160f, 780f, 25f, 0xffffdc73.toInt(), true)
        levelNames.forEachIndexed { index, level ->
            val y = 842f + index * 48f
            text(canvas, level, 110f, y, 30f, levelColor(level), true)
            text(canvas, save.profile.cleared.getOrElse(index) { 0 }.toString(), 690f, y, 31f, Color.WHITE, true)
            text(canvas, save.profile.fullCombo.getOrElse(index) { 0 }.toString(), 940f, y, 31f, 0xff8ff0ff.toInt(), true)
            text(canvas, save.profile.phi.getOrElse(index) { 0 }.toString(), 1170f, y, 31f, 0xffffdc73.toInt(), true)
        }

        glass(canvas, RectF(56f, 1050f, 1384f, 1432f), 34f)
        text(canvas, "TOP RECORDS", 88f, 1118f, 34f, 0xff80e6ff.toInt(), true)
        snapshot.sorted.take(5).forEachIndexed { index, record ->
            val y = 1180f + index * 52f
            text(canvas, "#${index + 1}", 92f, y, 24f, 0xff95a8bd.toInt(), true)
            text(canvas, ellipsize(record.title, 30), 170f, y, 25f, Color.WHITE)
            text(canvas, "${record.level} ${format(record.constant, 1)}", 820f, y, 24f, levelColor(record.level), true)
            text(canvas, "${format(record.accuracy, 4)}%", 1010f, y, 24f, Color.WHITE)
            text(canvas, format(record.rks, 4), 1240f, y, 24f, 0xffffdc73.toInt(), true)
        }
        drawFooter(canvas, width.toFloat(), height - 48f)
        return generated(bitmap, "profile")
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xff07111f.toInt(), 0xff102c46.toInt(), 0xff08151f.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x183fd8ff
        repeat(16) { index ->
            val x = (index * 113 % width).toFloat()
            canvas.drawCircle(x, (index * 197 % height).toFloat(), 90f + index * 6f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0x3348c8ff
        val path = Path().apply {
            moveTo(-40f, 270f)
            lineTo(width * .46f, 85f)
            lineTo(width + 80f, 230f)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawHeader(canvas: Canvas, profile: PlayerProfile, snapshot: RksSnapshot, subtitle: String) {
        text(canvas, "PHIGROS", 58f, 110f, 72f, Color.WHITE, true)
        text(canvas, "DATA STUDIO", 62f, 158f, 28f, 0xff72e4ff.toInt(), true)
        text(canvas, subtitle, 58f, 250f, 34f, 0xffb8c7da.toInt())
        text(canvas, ellipsize(profile.playerId, 28), 520f, 112f, 54f, Color.WHITE, true)
        text(canvas, profile.selfIntro.ifBlank { "Cloud save analytics" }.let { ellipsize(it, 48) }, 524f, 162f, 25f, 0xffaabdd0.toInt())
        text(canvas, format(snapshot.overall, 4), 1080f, 126f, 74f, 0xffffdc73.toInt(), true)
        text(canvas, "RKS", 1280f, 164f, 25f, 0xffe8c85d.toInt(), true)
        text(canvas, "SAVE · ${profile.saveUpdatedAt.take(19).replace('T', ' ')}", 520f, 235f, 25f, 0xff82d9ee.toInt())
    }

    private fun drawRecordCard(
        canvas: Canvas,
        bounds: RectF,
        rank: String,
        record: ChartRecord,
        target: PushTarget?,
    ) {
        glass(canvas, bounds, 22f)
        val coverRect = RectF(bounds.left + 12f, bounds.top + 12f, bounds.left + 222f, bounds.bottom - 12f)
        val cover = loadCover(record)
        if (cover != null) drawCover(canvas, cover, coverRect) else {
            paint.color = levelColor(record.level)
            canvas.drawRoundRect(coverRect, 14f, 14f, paint)
            text(canvas, record.level, coverRect.left + 64f, coverRect.centerY() + 18f, 40f, Color.WHITE, true)
        }
        paint.color = 0xcc06111c.toInt()
        canvas.drawRoundRect(RectF(bounds.left + 10f, bounds.top + 9f, bounds.left + 98f, bounds.top + 51f), 11f, 11f, paint)
        text(canvas, rank, bounds.left + 25f, bounds.top + 41f, 26f, 0xffffdc73.toInt(), true)

        val x = bounds.left + 242f
        text(canvas, ellipsize(record.title, 24), x, bounds.top + 42f, 27f, Color.WHITE, true)
        text(canvas, "${record.level}  ${format(record.constant, 1)}  ·  ${record.rating}", x, bounds.top + 77f, 22f, levelColor(record.level), true)
        text(canvas, "%07d".format(record.score), x, bounds.top + 112f, 28f, Color.WHITE, true)
        text(canvas, "ACC ${format(record.accuracy, 4)}%", x + 180f, bounds.top + 112f, 22f, 0xff9edceb.toInt())
        text(canvas, "RKS ${format(record.rks, 4)}", x, bounds.top + 141f, 21f, 0xffffdc73.toInt(), true)
        text(canvas, "推分 ${target?.label ?: "—"}", x + 220f, bounds.top + 141f, 21f, 0xff9df7c2.toInt())
    }

    private fun glass(canvas: Canvas, bounds: RectF, radius: Float) {
        paint.color = 0x9a14283b.toInt()
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x664bdfff
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun loadCover(record: ChartRecord): Bitmap? {
        if (record.illustrationUrl.isBlank()) return null
        val cache = File(coverDirectory, sha1(record.illustrationUrl) + ".png")
        if (!cache.exists()) {
            runCatching {
                val bytes = PhigrosHttp.getBytes(record.illustrationUrl)
                if (bytes.size > 1024) cache.writeBytes(bytes)
            }
        }
        return if (cache.exists()) BitmapFactory.decodeFile(cache.absolutePath) else null
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        val targetRatio = destination.width() / destination.height()
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val source = if (sourceRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).toInt()
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).toInt()
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        canvas.save()
        val clip = Path().apply { addRoundRect(destination, 14f, 14f, Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.shader = null
        paint.alpha = 255
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, source, destination, paint)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, width: Float, baseline: Float) {
        text(canvas, "Generated by Android Tool Suite · Phigros Data Studio 2.0.5", 58f, baseline, 25f, 0xff86a9bf.toInt())
        text(canvas, "P3 + B27 · Local-first · SessionToken encrypted", width - 600f, baseline, 23f, 0xff6f92a8.toInt())
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }

    private fun generated(bitmap: Bitmap, kind: String): GeneratedImage {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return GeneratedImage(bitmap, "phigros-$kind-$timestamp.png", kind)
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ellipsize(value: String, max: Int): String = if (value.length <= max) value else value.take(max - 1) + "…"
    private fun format(value: Double, digits: Int): String = "%.${digits}f".format(Locale.ROOT, value)
    private fun levelColor(level: String): Int = when (level) {
        "EZ" -> 0xff54c889.toInt()
        "HD" -> 0xff4ea4ef.toInt()
        "IN" -> 0xffe65067.toInt()
        "AT" -> 0xffb78cff.toInt()
        else -> 0xff8fa6b8.toInt()
    }
}
