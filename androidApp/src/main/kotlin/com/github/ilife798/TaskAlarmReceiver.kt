package com.github.ilife798

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * 「后台运行」模式的闹钟落点。
 *
 * 前台运行不经过这里：那种情况下闹钟用的是 Activity 型 PendingIntent，直接拉起 [MainActivity]。
 * 后台运行则发出 [TaskScheduler.ACTION_RUN_BACKGROUND_TASKS]，由本接收器把执行排进 WorkManager。
 *
 * 为什么必须经 WorkManager：Android 12+ 禁止应用在后台启动前台服务，直接在广播里
 * `startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException`；而 `goAsync()`
 * 只有约 10 秒额度，跑不完一轮积分任务。WorkManager 是系统认可的合法后台通道。
 *
 * 这个接收器不需要导出：闹钟的 PendingIntent 由本应用自己创建，天然带本应用身份。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != TaskScheduler.ACTION_RUN_BACKGROUND_TASKS) return
        val appContext = context.applicationContext
        ScheduledTasks.bind(appContext)
        // 闹钟可能是关闭计划之前设下的遗留，到点要再确认一次
        if (!ScheduledTasks.currentSchedule().enabled) return
        enqueueBackgroundWork(appContext)
        // 一次性闹钟用完即弃，立刻按计划重排下一次
        ScheduledTasks.scheduleNext()
    }

    private fun enqueueBackgroundWork(context: Context) {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<TaskBackgroundWorker>()
                    // expedited：请系统尽快调度；配额不足时降级为普通任务而不是直接丢弃
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager
                .getInstance(context)
                // KEEP：上一轮还没跑完就不再排第二轮，避免两个 Worker 同时打同一批接口
                .enqueueUniqueWork(
                    TaskScheduler.BACKGROUND_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }.onFailure { logDebug("ILife798", "TaskAlarmReceiver.enqueue: ${it.message}") }
    }
}
