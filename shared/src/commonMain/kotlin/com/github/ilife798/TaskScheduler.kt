package com.github.ilife798

import com.github.ilife798.data.model.TaskSchedule

/**
 * 积分任务定时执行的平台调度入口。
 *
 * 定时能力是纯平台特性：Android 用 AlarmManager 排系统闹钟，iOS 需要在 Info.plist
 * 声明后台任务标识并由系统按使用习惯调度，语义差异过大，本期只实现 Android。
 * 不支持的平台实现为 no-op，UI 据此展示「暂不支持」。
 */
expect object TaskScheduler {
    /** 当前平台是否支持定时执行。 */
    val supported: Boolean

    /** 按 [schedule] 重排下一次触发；未启用或平台不支持时等价于 [cancel]。 */
    fun reschedule(schedule: TaskSchedule)

    /** 取消已排的触发。 */
    fun cancel()

    /** 「下次执行」的可读描述，平台不支持或未启用时返回 null。 */
    fun describeNextRun(schedule: TaskSchedule): String?
}
