package com.example.shizukuaccessibilitygrant.plugins.phigros

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrBitmap {
    fun encode(value: String, size: Int = 640): Bitmap {
        val matrix = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
