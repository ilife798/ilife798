package com.github.ilife798.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

// Android 用 zxing 编码：低纠错（L）以容纳更多字节，白边留 1 模块避免贴边裁切。
actual fun encodeQrCodePng(
    text: String,
    sizePx: Int,
): ByteArray? =
    runCatching {
        val hints =
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1,
            )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val rowOffset = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        val bytes =
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        bytes
    }.getOrNull()
