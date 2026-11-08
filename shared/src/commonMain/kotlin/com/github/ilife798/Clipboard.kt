package com.github.ilife798

// 写入系统剪贴板，成功返回 true。
expect fun copyTextToClipboard(text: String): Boolean

// 读取系统剪贴板中的纯文本；为空或无法读取时返回 null。
expect fun readTextFromClipboard(): String?

// 复制文本并提示结果。
fun copyToClipboard(
    text: String,
    successMessage: String,
) {
    showToast(if (copyTextToClipboard(text)) successMessage else "复制失败")
}
