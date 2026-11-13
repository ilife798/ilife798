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
}
