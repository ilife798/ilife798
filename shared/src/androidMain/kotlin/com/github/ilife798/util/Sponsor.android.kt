package com.github.ilife798.util

import android.content.Intent
import androidx.core.net.toUri
import com.github.ilife798.ApplicationContext
import com.github.ilife798.logDebug

actual fun openSponsorPage(url: String): Boolean =
    try {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ApplicationContext.instance.startActivity(intent)
        true
    } catch (e: Exception) {
        logDebug("ILife798", "openSponsorPage: ${e.message}")
        false
    }
