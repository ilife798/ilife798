package com.github.ilife798.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.github.ilife798.toImageBitmap

/**
 * 把文本编码成二维码 PNG 字节。内容超过二维码容量（低纠错 L 级约 2.9KB）时返回 null，
 * 由调用方提示改用文件或剪贴板。
 */
expect fun encodeQrCodePng(
    text: String,
    sizePx: Int = 512,
): ByteArray?

/** 二维码长度上限：留出余量，避免贴近二维码容量边界导致扫码不稳定。 */
const val BACKUP_QR_MAX_CHARS: Int = 2400

/** 生成可直接绘制的二维码位图；编码或解码失败时返回 null。 */
@Composable
fun rememberQrCodeBitmap(text: String): ImageBitmap? =
    remember(text) {
        encodeQrCodePng(text)?.let { bytes ->
            runCatching { bytes.toImageBitmap() }.getOrNull()
        }
    }
