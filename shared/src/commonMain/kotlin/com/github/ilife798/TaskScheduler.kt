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

    /**
     * 是否已获得「精确闹钟」权限。
     *
     * Android 14 起该权限默认不授予，未授予时只能退化成窗口闹钟：设备沉睡（Doze）期间
     * 触发会被推迟到下一次维护窗口，可能晚很久。拿不到权限就只能引导用户手动开启，
     * 所以这里把状态暴露给界面。不涉及该概念的平台恒为 true。
     */
    val exactAlarmGranted: Boolean

    /** 打开系统的「精确闹钟」授权页面；平台不符或无此页面时不作处理。 */
    fun openExactAlarmSettings()

    /**
     * 让后台通道保持常驻（拉起保活前台服务）。
     *
     * 目的不是「执行任务」而是「让进程活着」：系统冻结普通后台进程后，闹钟广播投递不进来、
     * WorkManager 也拿不到 CPU。挂着前台服务的进程优先级高得多，到点才有机会真正开跑。
     * 未启用后台运行或平台不支持时实现为 no-op。
     */
    fun startBackgroundRun()

    /**
     * 停止正在执行的后台通道（前台服务与排队中的 WorkManager 任务）。
     *
     * 任务页的停止按钮要能停下后台那一轮，但界面层拿不到平台服务类，只有这里能落地。
     * 没有后台通道的平台实现为 no-op。
     */
    fun stopBackgroundRun()
}
