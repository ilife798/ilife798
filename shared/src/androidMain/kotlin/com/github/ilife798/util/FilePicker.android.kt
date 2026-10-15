package com.github.ilife798.util

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.github.ilife798.ApplicationContext
import com.github.ilife798.logDebug

/**
 * Android 侧读取文本：走系统「打开文件」界面（SAF），不需要任何存储权限。
 *
 * 用 `OpenDocument` 而不是 `GetContent`：前者返回的 Uri 语义更稳定，云盘提供方下表现也
 * 更好。这里只读一次、用完即弃，不需要 takePersistableUriPermission。
 */
@Composable
actual fun rememberTextFilePicker(): TextFilePicker {
    // 回调要跨「发起选择 → 系统界面返回」这段时间存活，且每次 pick 都要换成最新调用方，
    // 故用 remember 保存的可变持有器，而不是组合期捕获一次的 lambda。
    val holder = remember { PickedCallbackHolder() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            holder.take()?.invoke(uri?.let { readText(it) })
        }
    return remember(launcher) {
        object : TextFilePicker {
            override fun pick(onPicked: (String?) -> Unit) {
                holder.set(onPicked)
                // 显式列出可接受的类型：传 null 会被部分系统版本理解为不过滤，与预期不符。
                // 备份是 JSON，但用户可能被网盘改过扩展名，故一并允许 text/plain。
                launcher.launch(arrayOf(BACKUP_PICK_MIME_TYPE, "text/plain"))
            }
        }
    }
}

/** 暂存「用户选完文件后要通知谁」，取用即清空，避免重复触发。 */
private class PickedCallbackHolder {
    private var callback: ((String?) -> Unit)? = null

    fun set(onPicked: (String?) -> Unit) {
        callback = onPicked
    }

    fun take(): ((String?) -> Unit)? {
        val current = callback
        callback = null
        return current
    }
}

private fun readText(uri: Uri): String? =
    try {
        val resolver = ApplicationContext.instance.contentResolver
        // 部分提供方（尤其网盘）拿不到 length，返回 -1，此时只能读完再判断
        val declaredSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (declaredSize > BACKUP_MAX_FILE_BYTES) {
            logDebug("ILife798", "pickTextFile: 文件过大 ${declaredSize}B，拒绝读取")
            null
        } else {
            resolver.openInputStream(uri)?.use { readCapped(it) }
        }
    } catch (e: Exception) {
        logDebug("ILife798", "pickTextFile 读取失败: ${e.message}")
        null
    }

/**
 * 读到流末尾，但超过上限即放弃。
 *
 * 不能直接 `readBytes()` 后再判断：那时内存已经吃进去了，误选一个几百 MB 的文件会直接
 * OOM。这里边读边累计，越界立刻返回 null。
 */
private fun readCapped(stream: java.io.InputStream): String? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        buffer.write(chunk, 0, read)
        if (buffer.size() > BACKUP_MAX_FILE_BYTES) {
            logDebug("ILife798", "pickTextFile: 实读超过上限，放弃")
            return null
        }
    }
    return buffer.toByteArray().toString(Charsets.UTF_8)
}
