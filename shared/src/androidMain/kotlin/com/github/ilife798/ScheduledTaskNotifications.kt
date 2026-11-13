package com.github.ilife798

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.github.ilife798.data.model.TaskProgress

/**
 * 定时任务（后台运行）用的通知入口。
 *
 * 只是 [buildTaskNotification] / [buildTaskResultNotification] / [ensureRunChannels] 的公开转发层：
 * 那几个函数是 shared 模块 internal 的，androidApp 侧的 Worker 跨模块访问不到。
 *
 * **刻意与手动运行共用同一个 id 与同一个构建函数**：用户在任务页点「运行选中账号」、
 * 定时任务走前台、定时任务走后台，看到的必须是同一条通知（`积分:N` / `任务名 (X/X)`）。
 * 过去后台通道另用 2002 加一套纯文本版，等于凭空多出一条通知，已废弃。
 */
object ScheduledTaskNotifications {
    /** 后台运行的进度通知 id：与手动运行的 `RUN_NOTIFICATION_ID` 是同一个。 */
    const val RUNNING_ID = RUN_NOTIFICATION_ID

    /** 本轮结果汇总的 id，独立于进度通知，可单独划掉。 */
    const val RESULT_ID = 2003

    /**
     * 保活通知的 id：后台运行开启期间一直挂着的那条。
     *
     * 与 `RUNNING_ID` 分开是必须的：一个服务同时只持有一条前台通知，保活期间它是常驻的；
     * 如果拿进度通知 2001 去当前台通知，跑完一轮就撤掉，服务也就跟着失去前台地位被系统回收。
     */
    const val RESIDENT_ID = 2004

    fun ensureChannel(context: Context) = ensureRunChannels(context)

    /**
     * 保活通知：后台运行开着就一直挂着。
     *
     * @param stopIntent 通知上「停止」按钮的意图，点它等于关掉后台运行。
     * @param nextRun 下次执行的可读时间，未知时传 null。
     */
    fun residentNotification(
        context: Context,
        stopIntent: PendingIntent,
        nextRun: String?,
    ): Notification = buildResidentNotification(context, stopIntent, nextRun)

    /** 清掉可能残留的进度通知（进程被杀后它会永远停在「运行中」）。 */
    fun cancelProgress(context: Context) {
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.cancel(RUNNING_ID)
        }
    }

    /**
     * 撤掉保活通知。
     *
     * 服务被 [android.app.Service.stopService] 停掉时框架通常会把前台通知一起收走，
     * 但国产 ROM（ColorOS/HyperOS 等）上有保留的实例，所以关闭后台运行、服务发现
     * 计划已失效时都显式再取消一次，避免「关掉了通知还挂在那」。
     */
    fun cancelResident(context: Context) {
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.cancel(RESIDENT_ID)
        }
    }

    /** 走常驻通道时，进度通知改用普通通知投递，避免与前台通知抢同一个槽位。 */
    fun notifyProgress(
        context: Context,
        progress: TaskProgress,
        accountLabel: String?,
    ) {
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.notify(RUNNING_ID, progressNotification(context, progress, accountLabel))
        }
    }

    fun canNotify(context: Context): Boolean = canPostNotification(context, CHANNEL)

    /**
     * 进度通知：与手动运行完全一致的两行正文。
     *
     * @param accountLabel 多账号时传账号显示名（拼在积分行前），单账号传 null。
     */
    fun progressNotification(
        context: Context,
        progress: TaskProgress,
        accountLabel: String?,
    ): Notification = buildTaskNotification(context, progress, accountLabel)

    /** 本轮结果汇总：一账号一行，可划掉。 */
    fun resultNotification(
        context: Context,
        title: String,
        text: String,
    ): Notification = buildTaskResultNotification(context, title, text)

    private const val CHANNEL = "task_running"
}
