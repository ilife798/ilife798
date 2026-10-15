package com.github.ilife798.util

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.github.ilife798.ApplicationContext
import com.github.ilife798.logDebug

/**
 * Android 侧保存文本：走系统「创建文档」界面（SAF），不需要任何存储权限。
 *
 * `rememberLauncherForActivityResult` 在组合期间注册，满足 ActivityResultRegistry
 * 「必须在 STARTED 之前注册」的约束。若改在点击回调里调用
 * `Activity.registerForActivityResult`，注册时 Activity 已 RESUMED，会抛
 * `IllegalStateException: LifecycleOwners must call register before they are STARTED`。
 */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val pending = remember { PendingTextFile() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            pending.finish(uri)
        }
    return remember(launcher) {
        object : TextFileSaver {
            override fun save(
                fileName: String,
                content: String,
                onFinished: (Boolean) -> Unit,
            ) {
                pending.start(content, onFinished)
                launcher.launch(fileName)
            }
        }
    }
}

/** 在「选好目标文件」与「拿到 Uri」之间暂存待写内容与回调。 */
private class PendingTextFile {
    private var content: String? = null
    private var onFinished: ((Boolean) -> Unit)? = null

    fun start(
        content: String,
        onFinished: (Boolean) -> Unit,
    ) {
        this.content = content
        this.onFinished = onFinished
    }

    fun finish(uri: Uri?) {
        val text = content
        val callback = onFinished
        content = null
        onFinished = null
        if (uri == null || text == null) {
            callback?.invoke(false)
            return
        }
        val ok = writeText(uri, text)
        callback?.invoke(ok)
    }
}

private fun writeText(
    uri: Uri,
    content: String,
): Boolean =
    try {
        ApplicationContext.instance.contentResolver
            .openOutputStream(uri, "wt")
            ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        true
    } catch (e: Exception) {
        logDebug("ILife798", "saveTextToFile 写入失败: ${e.message}")
        false
    }
