package com.github.ilife798

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual fun copyTextToClipboard(text: String): Boolean =
    try {
        val manager = ApplicationContext.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("ilife798", text))
        true
    } catch (_: Exception) {
        false
    }

actual fun readTextFromClipboard(): String? =
    try {
        val manager = ApplicationContext.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        val text =
            clip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(ApplicationContext.instance)
                ?.toString()
        text?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
