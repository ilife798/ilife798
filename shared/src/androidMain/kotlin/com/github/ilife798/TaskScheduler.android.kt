package com.github.ilife798

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.work.WorkManager
import com.github.ilife798.data.model.TaskSchedule
import com.github.ilife798.util.formatTimestamp
import java.util.Calendar

/**
 * Android 侧的定时调度。
 *
 * 两种运行方式走两条通道，闹钟到点后各自选最可靠的方式把活干完：
 * - **前台运行**：用 [PendingIntent.getActivity] 拉起 MainActivity，走 `AppRequests.runScheduledTasks`，
 *   与手动按下「运行选中账号」共用同一套切号与执行逻辑。Android 10+ 禁止后台启动 Activity，
 *   但由闹钟 PendingIntent 启动属于系统豁免路径，因此这条通道可用。
 * - **后台运行**：闹钟发广播，`TaskAlarmReceiver` 先尝试拉起常驻前台服务 `ScheduledTaskService`
 *   （前台服务的进程优先级高，ColorOS 的 fastfreezer 之类不会去冻它）；
 *   若系统以 `ForegroundServiceStartNotAllowedException` 拒绝，再降级交给 WorkManager 的
 *   expedited 一次性任务。两条路最终都调 `TaskBackgroundRunner`，不碰界面。
 *
 * 闹钟本身仍是 AlarmManager，优先级从高到低：
 * 1. `setAlarmClock`：完全豁免 Doze，代价是状态栏会显示闹钟图标，需要精确闹钟权限；
 * 2. `setExactAndAllowWhileIdle`：Doze 下仍能唤醒，同样需要精确闹钟权限；
 * 3. `setWindow`：没有权限时的兜底，会被推到下一个 maintenance window，可能晚很久。
 */
actual object TaskScheduler {
    private const val REQUEST_CODE = 7301

    // 后台通道用独立的 PendingIntent 身份，避免与 Activity 型闹钟互相覆盖。
    private const val REQUEST_CODE_BACKGROUND = 7302

    // setAlarmClock 的「点闹钟图标」入口，与真正的触发 PendingIntent 分开。
    private const val REQUEST_CODE_SHOW = 7303

    /** 后台运行时闹钟发出的广播，由 `TaskAlarmReceiver` 接手并转交 WorkManager。 */
    const val ACTION_RUN_BACKGROUND_TASKS = "com.github.ilife798.action.RUN_BACKGROUND_TASKS"

    /** 后台任务在 WorkManager 队列里的唯一名称：同一时刻只允许一轮在跑。 */
    const val BACKGROUND_WORK_NAME = "ilife798_scheduled_tasks"

    private const val BACKGROUND_RECEIVER_CLASS = "com.github.ilife798.TaskAlarmReceiver"

    // 无法申请精确闹钟时的容错窗口：向后放宽 10 分钟，避免系统把触发时间推到过远。
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    actual val supported: Boolean = true

    /** Android 14 起该权限默认不授予，未授予时只能退化成窗口闹钟。 */
    actual val exactAlarmGranted: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return false
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        }

    actual fun openExactAlarmSettings() {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        val packageUri = "package:${context.packageName}".toUri()
        val intents =
            listOf(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (started) return
        }
        logDebug("ILife798", "TaskScheduler.openExactAlarmSettings: 无法打开授权页面")
    }

    actual fun reschedule(schedule: TaskSchedule) {
        val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
        val normalized = schedule.normalized
        // 只有「不再是后台运行」时才清空后台队列。
        // 不无条件 cancelWork 的原因：应用每次启动都会经 loadSettings 调到这里，
        // 若后台任务正在跑，无条件取消就等于用户一打开应用就把本轮任务掐断。
        if (!normalized.enabled || !normalized.runInBackground) cancelWork(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // 两条通道的闹钟都要清掉：只取消目标通道时，切换运行方式会残留另一条，
        // 表现为切到后台后仍被 Activity 型闹钟拉起界面，或切回前台后多触发一轮。
        alarmManager.cancel(pendingIntent(context, null))
        alarmManager.cancel(pendingIntent(context, TaskSchedule(runInBackground = true)))
        if (!normalized.enabled) return
        val triggerAt = nextTriggerAt(normalized, System.currentTimeMillis())
        setAlarm(alarmManager, triggerAt, pendingIntent(context, normalized))
        // 精确闹钟被拒时系统会退回窗口闹钟，用户只表现为「到点没反应」，
        // 这行日志把两件事一起记下来：走的是哪条通道、能不能申请到精确闹钟。
        logDebug(
            "ILife798",
            "TaskScheduler.reschedule: mode=${normalized.runModeText} triggerAt=$triggerAt " +
                "exact=${Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()}",
        )
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
                // setAlarmClock 是唯一完全豁免 Doze 的形式，代价是状态栏常驻一个闹钟图标。
                // 定时任务是「到点就得跑」的场景，值得用这个图标换准点。
                val showIntent =
                    pendingIntentShowIntent(pendingIntent)
                if (showIntent != null) {
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                // 未授予精确闹钟权限时退回窗口闹钟：仍会唤醒设备，只是触发时间可能推迟几分钟。
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, INEXACT_WINDOW_MS, pendingIntent)
            }
        }.onFailure {
            logDebug("ILife798", "TaskScheduler.setAlarm: ${it.message}")
        }
    }

    /**
     * `setAlarmClock` 需要一个「点闹钟图标要打开哪」的 PendingIntent。
     *
     * 这里固定指向任务页，让用户点状态栏图标能直接看到定时任务的执行情况；
     * 拿不到 Activity 型 PendingIntent 时返回 null，调用方退回 `setExactAndAllowWhileIdle`。
     */
    private fun pendingIntentShowIntent(operation: PendingIntent): PendingIntent? =
        runCatching {
            val context = ApplicationContext.instance
            if (operation.isActivity) null else activityPendingIntentForShow(context)
        }.getOrNull()

    private fun activityPendingIntentForShow(context: Context): PendingIntent {
        val intent =
            (
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            ).apply {
                action = IntentActions.ACTION_OPEN_TASKS
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
