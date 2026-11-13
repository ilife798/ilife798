package com.github.ilife798

import android.app.Notification
import android.content.Context

/**
 * 定时任务（后台运行）用的通知入口。
 *
 * 只是 [buildTaskNotification] / [buildTaskResultNotification] / [ensureRunChannels] 的公开转发层：
 * 那几个函数是 shared 模块 internal 的，androidApp 侧的 Worker 跨模块访问不到。
 * 样式、通道、图标、点击去向全部复用积分任务那一套，这里不另起视觉。
 */
object ScheduledTaskNotifications {
    /** 后台运行的进度通知 id，与手动任务（2001）区分开。 */
    const val RUNNING_ID = 2002

    /** 本轮结果汇总的 id。 */
    const val RESULT_ID = 2003

    fun ensureChannel(context: Context) = ensureRunChannels(context)

    fun canNotify(context: Context): Boolean = canPostNotification(context, CHANNEL)

    /**
     * 后台进度通知：复用积分任务通知的图标、通道、分组、点击去向，
     * 只把正文换成 [text]。后台执行没有「任务名 (X/X)」这种细粒度进度，进度是账号级的。
     */
    fun runningNotification(
        context: Context,
        text: String,
    ): Notification = buildTaskNotification(context, text)

    /** 本轮结果汇总：一账号一行，可划掉。 */
    fun resultNotification(
        context: Context,
        title: String,
        text: String,
    ): Notification = buildTaskResultNotification(context, title, text)

    private const val CHANNEL = "task_running"
}
