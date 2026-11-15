package com.github.ilife798.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

// iOS：UIApplication.openURL 交给系统打开（openURL 不需要 LSApplicationQueriesSchemes 白名单，
// 那个白名单只对 canOpenURL 生效）。
actual fun openExternalUrl(url: String): Boolean =
    runCatching {
        val nsUrl = NSURL(string = url)
        UIApplication.sharedApplication.openURL(nsUrl)
    }.getOrDefault(false)
