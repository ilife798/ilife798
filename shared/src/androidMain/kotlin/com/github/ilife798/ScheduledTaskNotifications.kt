package com.github.ilife798

import android.app.Notification
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

    fun ensureChannel(context: Context) = ensureRunChannels(context)

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
