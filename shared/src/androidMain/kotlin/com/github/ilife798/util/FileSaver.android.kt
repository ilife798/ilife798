package com.github.ilife798.util

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.ilife798.ActivityHolder
import com.github.ilife798.ApplicationContext
import com.github.ilife798.logDebug

/**
 * Android 侧保存文本：走系统「创建文档」界面（SAF），不需要任何存储权限。
 *
 * `rememberLauncherForActivityResult` 只能在 Composable 中注册，而保存动作发生在点击回调
 * 里，因此这里直接向 Activity 注册一次性 launcher 并在回调中写文件。Activity 在配置变更后
 * 会被替换，故每次调用都重新注册，不缓存 launcher。
 */
actual fun saveTextToFile(
    fileName: String,
    content: String,
    mimeType: String,
): Boolean {
    val activity = ActivityHolder.current as? ComponentActivity
    if (activity == null) {
        logDebug("ILife798", "saveTextToFile: 没有可用的 Activity")
        return false
    }
    return try {
        val launcher =
            activity.registerForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
                uri?.let { writeText(it, content) }
            }
        launcher.launch(fileName)
        true
    } catch (e: Exception) {
        logDebug("ILife798", "saveTextToFile: ${e.message}")
        false
    }
}

private fun writeText(
    uri: Uri,
    content: String,
) {
    try {
        ApplicationContext.instance.contentResolver
            .openOutputStream(uri, "wt")
            ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    } catch (e: Exception) {
        logDebug("ILife798", "saveTextToFile 写入失败: ${e.message}")
    }
}
