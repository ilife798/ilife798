package com.github.ilife798.util

import com.github.ilife798.data.model.KeepAliveVendor

// iOS 没有「自启动 / 后台运行」这类厂商开关，保活由系统统一决定，
// 所以这里只给出一个中性的兜底值，界面不会展示 Android 那套厂商引导。
actual fun keepAliveVendor(): KeepAliveVendor = KeepAliveVendor.AOSP

actual fun openKeepAliveSettings() {}

actual fun openRecentTasks() {}
