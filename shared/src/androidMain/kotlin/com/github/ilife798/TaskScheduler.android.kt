package com.github.ilife798

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.WorkManager
import com.github.ilife798.data.model.TaskSchedule
import com.github.ilife798.util.formatTimestamp
import java.util.Calendar

/**
 * Android 侧的定时调度。
 *
 * 两种运行方式走两条完全不同的通道，因为系统对它们的限制不同：
 * - **前台运行**：用 [PendingIntent.getActivity] 拉起 MainActivity，走 `AppRequests.runScheduledTasks`，
 *   与手动按下「运行选中账号」共用同一套切号与执行逻辑。Android 10+ 禁止后台启动 Activity，
 *   但由闹钟 PendingIntent 启动属于系统豁免路径，因此这条通道可用。
 * - **后台运行**：交给 WorkManager 的 expedited 一次性任务，由系统调度、用
 *   `TaskBackgroundWorker` 直接对每个账号发请求，不碰界面。Android 12+ 禁止后台启动前台服务，
 *   所以这里不走自建 Service；WorkManager 是系统认可的合法后台执行通道。
 *
 * 闹钟本身仍然是 AlarmManager：`setExactAndAllowWhileIdle` 能在 Doze 下唤醒设备，
 * 这是「到点必须跑」需要的行为，WorkManager 的周期性任务做不到准点。
 */
actual object TaskScheduler {
    private const val REQUEST_CODE = 7301

    // 后台通道用独立的 PendingIntent 身份，避免与 Activity 型闹钟互相覆盖。
    private const val REQUEST_CODE_BACKGROUND = 7302

    /** 后台运行时闹钟发出的广播，由 `TaskAlarmReceiver` 接手并转交 WorkManager。 */
    const val ACTION_RUN_BACKGROUND_TASKS = "com.github.ilife798.action.RUN_BACKGROUND_TASKS"

    /** 后台任务在 WorkManager 队列里的唯一名称：同一时刻只允许一轮在跑。 */
    const val BACKGROUND_WORK_NAME = "ilife798_scheduled_tasks"

    private const val BACKGROUND_RECEIVER_CLASS = "com.github.ilife798.TaskAlarmReceiver"

    // 无法申请精确闹钟时的容错窗口：向后放宽 10 分钟，避免系统把触发时间推到过远。
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    actual val supported: Boolean = true

    actual fun reschedule(schedule: TaskSchedule) {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        val normalized = schedule.normalized
        // 两条通道互斥：先无条件清掉两条通道上的遗留调度，再按当前运行方式装新的。
        // 只取消目标通道是不够的——用户从前台切到后台时，残留的 Activity 型闹钟仍会到点拉起界面；
        // 反过来切回前台时，残留的广播型闹钟也会多触发一轮。
        cancelWork(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context, null))
        alarmManager.cancel(pendingIntent(context, TaskSchedule(runInBackground = true)))
        if (!normalized.enabled) return
        val triggerAt = nextTriggerAt(normalized, System.currentTimeMillis())
        setAlarm(alarmManager, triggerAt, pendingIntent(context, normalized))
    }

    actual fun cancel() {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        cancelWork(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context, null))
        alarmManager.cancel(pendingIntent(context, TaskSchedule(runInBackground = true)))
    }

    actual fun describeNextRun(schedule: TaskSchedule): String? {
        if (!schedule.enabled) return null
        val triggerAt = nextTriggerAt(schedule, System.currentTimeMillis())
        return formatTimestamp(triggerAt, "M月d日 HH:mm")
    }

    private fun setAlarm(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent,
    ) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                // 未授予精确闹钟权限时退回窗口闹钟：仍会唤醒设备，只是触发时间可能推迟几分钟。
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, INEXACT_WINDOW_MS, pendingIntent)
            }
        }.onFailure {
            logDebug("ILife798", "TaskScheduler.setAlarm: ${it.message}")
        }
    }

    /**
     * 按运行方式返回到点要触发的 PendingIntent。
     *
     * - 后台运行：广播型，交给 `TaskAlarmReceiver` 把执行交给 WorkManager；
     * - 前台运行（或 [schedule] 为空时用于取消）：Activity 型，直接拉起 MainActivity。
     *
     * 两种类型不能混用同一个 requestCode，否则 `PendingIntent.getActivity` 取到的
     * 可能是先前用 `getBroadcast` 创建的实例，所以后台单独用一个 requestCode。
     */
    private fun pendingIntent(
        context: Context,
        schedule: TaskSchedule?,
    ): PendingIntent =
        if (schedule?.runInBackground == true) {
            val intent =
                Intent(ACTION_RUN_BACKGROUND_TASKS).apply {
                    setClassName(context.packageName, BACKGROUND_RECEIVER_CLASS)
                }
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BACKGROUND,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            val intent =
                (
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
                ).apply {
                    action = IntentActions.ACTION_RUN_SCHEDULED_TASKS
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                }
            // 不可变标志：闹钟只在本应用内部流转，不需要被外部篡改。
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    /** 取消后台通道上遗留的一次性任务；前台通道没有排队任务，调用它是无副作用的。 */
    private fun cancelWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK_NAME)
        }.onFailure { logDebug("ILife798", "TaskScheduler.cancelWork: ${it.message}") }
    }

    /** 从 [now] 起算的下一次触发时刻；计划未启用或没有可用星期时退回「明天此刻」。 */
    internal fun nextTriggerAt(
        schedule: TaskSchedule,
        now: Long,
    ): Long {
        val normalized = schedule.normalized
        val base = Calendar.getInstance()
        base.timeInMillis = now
        base.set(Calendar.HOUR_OF_DAY, normalized.hour)
        base.set(Calendar.MINUTE, normalized.minute)
        base.set(Calendar.SECOND, 0)
        base.set(Calendar.MILLISECOND, 0)
        // 最多向前找 8 天：一周内必然命中，多留一天兜住「今天该时刻已过」的情况。
        for (offset in 0..8) {
            val candidate = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            if (candidate.timeInMillis <= now && offset == 0) continue
            val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
            if (!normalized.isActiveOn(dayOfWeek)) continue
            if (candidate.timeInMillis > now) return candidate.timeInMillis
        }
        // 理论上走不到这里；退回「明天同一时刻」，至少保证还有一个触发点。
        return (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    }
}
