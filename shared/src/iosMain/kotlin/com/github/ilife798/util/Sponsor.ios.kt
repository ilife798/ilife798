package com.github.ilife798.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

// 打开赞助页：UIApplication.openURL。
actual fun openSponsorPage(url: String): Boolean =
    runCatching {
        UIApplication.sharedApplication.openURL(NSURL(string = url))
    }.getOrDefault(false)
