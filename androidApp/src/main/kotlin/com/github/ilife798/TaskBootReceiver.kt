package com.github.ilife798

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.ilife798.data.model.TaskSchedule
import com.github.ilife798.data.model.decodeTaskSchedule

/**
 * 开机与升级后的重排入口。
 *
 * 重启会清空所有闹钟，不重排就等于定时任务悄悄失效，所以这个接收器必须导出——
 * 系统广播的发送方是系统进程。两个 action 都是受保护广播，外部应用无法伪造。
 *
 * 闹钟到点后的执行入口不在这里：[TaskScheduler] 用的是 Activity 型的 PendingIntent，
 * 到点直接回到 [MainActivity]，与手动按下「运行选中账号」共用同一套逻辑。
 */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ScheduledTasks.bind(context.applicationContext)
                ScheduledTasks.scheduleNext()
            }
        }
    }
}

/**
 * 重排闹钟所需的初始化与读取逻辑。
 *
 * 接收器可能在进程刚被拉起、应用还没初始化时运行，[ApplicationContext] / [AppStorage]
 * 尚未赋值，统一在这里补齐，避免读取计划时抛 IllegalStateException。
 */
internal object ScheduledTasks {
    fun bind(context: Context) {
        ApplicationContext.instance = context
        // 同一个进程里反复赋值也不会出问题：底层是系统缓存的 SharedPreferences。
        AppStorage.instance = PersistentStorage(context)
    }

    fun currentSchedule(): TaskSchedule =
        runCatching { decodeTaskSchedule(AppStorage.instance.getString(StorageKeys.TASK_SCHEDULE)) }
            .getOrDefault(TaskSchedule())

    fun scheduleNext() {
        val schedule = currentSchedule()
        if (schedule.enabled) {
            TaskScheduler.reschedule(schedule)
            logDebug(
                "ILife798",
                "ScheduledTasks: 已重排 enabled=${schedule.enabled} mode=${schedule.runModeText} " +
                    "next=${TaskScheduler.describeNextRun(schedule)}",
            )
        } else {
            TaskScheduler.cancel()
            logDebug("ILife798", "ScheduledTasks: 计划未启用，已取消所有调度")
        }
    }
}
