package com.github.ilife798

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * 「后台运行」模式的闹钟落点。
 *
 * 前台运行不经过这里：那种情况下闹钟用的是 Activity 型 PendingIntent，直接拉起 [MainActivity]。
 * 后台运行则发出 [TaskScheduler.ACTION_RUN_BACKGROUND_TASKS]，由本接收器分两条通道执行：
 *
 * 1. 首选手动拉起 [ScheduledTaskService]（前台服务）。它的进程优先级高得多，国产 ROM 的冻结器
 *    （ColorOS 的 `fastfreezer` 等）基本不会动它，闹钟一到就能真正开始跑。
 * 2. 系统拒绝后台启动前台服务时（Android 12+ 在无精确闹钟权限等场景会抛
 *    `ForegroundServiceStartNotAllowedException`），降级到 WorkManager。它虽然要等系统调度、
 *    还可能被 Doze 压到 maintenance window，但至少是一条系统认可的合法后台通道。
 *
 * 两条通道执行的是同一份 [ScheduledTaskRunner]，通知样式也一致，用户不需要感知走的是哪条。
 *
 * 这个接收器不需要导出：闹钟的 PendingIntent 由本应用自己创建，天然带本应用身份。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action
        val appContext = context.applicationContext
        if (action != TaskScheduler.ACTION_RUN_BACKGROUND_TASKS) return
        logDebug("ILife798", "TaskAlarmReceiver: 收到后台闹钟广播")
        ScheduledTasks.bind(appContext)
        // 闹钟可能是关闭计划之前设下的遗留，到点要再确认一次
        val schedule = ScheduledTasks.currentSchedule()
        if (!schedule.enabled) {
            logDebug("ILife798", "TaskAlarmReceiver: 计划已关闭，忽略本次触发")
            return
        }
        if (!schedule.runInBackground) {
            logDebug("ILife798", "TaskAlarmReceiver: 运行方式已改为前台，忽略本次触发")
            return
        }
        // 顺序不能反：scheduleNext() 内部会 cancelWork() 清掉队列里的遗留任务，
        // 先排程再执行才是「清旧的、装新的」；反过来会把刚排进去的这一轮直接取消，
        // 表现就是后台模式到点毫无动静。
        ScheduledTasks.scheduleNext()
        if (!startResidentService(appContext)) {
            enqueueBackgroundWork(appContext)
        }
    }

    /**
     * 尝试拉起常驻前台服务。
     *
     * @return true 表示服务已被拉起（本轮交给服务执行），false 表示需要降级到 WorkManager。
     */
    private fun startResidentService(context: Context): Boolean {
        val intent = Intent(context, ScheduledTaskService::class.java)
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            logDebug("ILife798", "TaskAlarmReceiver: 已拉起前台服务执行")
            true
        }.getOrElse { e ->
            // Android 12+ 在后台启动前台服务受限；无精确闹钟权限的机型上这是常态，不算异常。
            // 其余异常同样降级，宁可慢一点也不要这一轮直接丢掉。
            val reason =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    "系统禁止后台启动前台服务"
                } else {
                    e.javaClass.simpleName
                }
            logDebug("ILife798", "TaskAlarmReceiver: 拉起前台服务失败（$reason），降级到 WorkManager")
            false
        }
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
                // REPLACE 而不是 KEEP：KEEP 的语义是「队列里还有未完成的同名任务就什么都不做」，
                // 一旦某一轮因为系统冻结卡在 ENQUEUED，后面每次闹钟都会静默丢弃、永久不跑。
                // 闹钟一天只响一次，同一时刻几乎不可能真有一轮在跑，用 REPLACE 把卡住的旧任务顶掉。
                .enqueueUniqueWork(
                    TaskScheduler.BACKGROUND_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                ).result
                .addListener(
                    {
                        logDebug("ILife798", "TaskAlarmReceiver: WorkManager 已接受排队")
                    },
                    // 直接跑在调用线程即可，这里只是打一行日志
                    { runnable -> runnable.run() },
                )
            logDebug("ILife798", "TaskAlarmReceiver: 已提交后台任务 ${request.id}")
        }.onFailure { logDebug("ILife798", "TaskAlarmReceiver.enqueue: ${it.message}") }
    }
}
