package com.github.ilife798

import com.github.ilife798.data.model.TaskSchedule

/**
 * iOS 侧占位实现：定时执行需要 Info.plist 声明 BGTaskScheduler 标识并由系统按使用习惯
 * 调度（不保证准点），与 Android 闹钟语义差异过大，本期不实现，UI 会提示暂不支持。
 */
actual object TaskScheduler {
    actual val supported: Boolean = false

    actual fun reschedule(schedule: TaskSchedule) = Unit

    actual fun cancel() = Unit

    actual fun describeNextRun(schedule: TaskSchedule): String? = null

    /** iOS 没有「精确闹钟权限」这一概念，恒 true 避免展示误导性的引导。 */
    actual val exactAlarmGranted: Boolean = true

    actual fun openExactAlarmSettings() = Unit

    /**
     * iOS 没有「保活服务」这种常驻进程，任务执行期间的存活由静音音频承担。
     * 语义保持与 Android 一致：申请让通道活着，不负责触发。
     */
    actual fun startBackgroundRun() = IosTaskKeepAlive.setEnabled(true)

    actual fun stopBackgroundRun() = IosTaskKeepAlive.setEnabled(false)
}
