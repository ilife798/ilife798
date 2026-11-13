package com.github.ilife798

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.ilife798.data.model.TaskSchedule
import com.github.ilife798.util.formatTimestamp
import java.util.Calendar

/**
 * Android 侧用 AlarmManager 排一个精确闹钟，到点后拉起应用并触发与任务页按钮等价的批量执行。
 *
 * 闹钟用的是 [PendingIntent.getActivity]：从 Android 12 起，后台启动前台服务受限，
 * 而由闹钟 PendingIntent 启动 Activity 属于系统豁免路径，因此这里不另起进程、不注册自定义
 * 服务，到点后直接回到 MainActivity 走 `AppRequests.runScheduledTasks`，与手动按下
 * 「运行选中账号」共用同一套切号与执行逻辑。
 *
 * 没有引入 WorkManager：本仓库依赖里没有它，而单次、按周几触发的场景用系统闹钟足够，
 * 且闹钟能在 Doze 下唤醒设备，这正是「到点必须跑」需要的行为。
 */
actual object TaskScheduler {
    private const val REQUEST_CODE = 7301

    // 无法申请精确闹钟时的容错窗口：向后放宽 10 分钟，避免系统把触发时间推到过远。
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    actual val supported: Boolean = true

    actual fun reschedule(schedule: TaskSchedule) {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(context)
        if (!schedule.enabled) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAt = nextTriggerAt(schedule, System.currentTimeMillis())
        setAlarm(alarmManager, triggerAt, pendingIntent)
    }

    actual fun cancel() {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
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

    private fun pendingIntent(context: Context): PendingIntent {
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
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
