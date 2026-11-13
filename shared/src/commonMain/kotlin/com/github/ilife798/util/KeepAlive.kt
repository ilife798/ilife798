package com.github.ilife798.util

import com.github.ilife798.data.model.KeepAliveVendor

// 识别当前设备是哪家定制系统，用来在保活引导里把用户所在厂商的步骤排到最前面。
expect fun keepAliveVendor(): KeepAliveVendor

// 跳转到该厂商的「自启动 / 后台运行」设置页；没有已知入口时退到应用详情页。
expect fun openKeepAliveSettings()

// 跳转到该厂商的任务列表界面，供用户给本应用加锁（防一键清理）。
expect fun openRecentTasks()
