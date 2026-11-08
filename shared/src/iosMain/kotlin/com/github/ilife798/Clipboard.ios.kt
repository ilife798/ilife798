package com.github.ilife798

import platform.UIKit.UIPasteboard

// iOS 剪贴板：UIPasteboard；写入失败/非字符串环境返回 false。
actual fun copyTextToClipboard(text: String): Boolean =
    runCatching {
        UIPasteboard.generalPasteboard.string = text
        true
    }.getOrDefault(false)

actual fun readTextFromClipboard(): String? =
    runCatching {
        UIPasteboard.generalPasteboard.string?.takeIf { it.isNotBlank() }
    }.getOrNull()
