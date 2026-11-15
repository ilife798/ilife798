package com.github.ilife798.util

import android.content.Intent
import androidx.core.net.toUri
import com.github.ilife798.ApplicationContext
import com.github.ilife798.logDebug

// Android：交给系统解析链接。调用方可能不在 Activity 上（如 ViewModel），统一加 NEW_TASK。
actual fun openExternalUrl(url: String): Boolean =
    try {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ApplicationContext.instance.startActivity(intent)
        true
    } catch (e: Exception) {
        // 没有应用能处理该 scheme 时 startActivity 会抛异常，按失败返回由调用方提示。
        logDebug("ILife798", "openExternalUrl: ${e.message}")
        false
    }
