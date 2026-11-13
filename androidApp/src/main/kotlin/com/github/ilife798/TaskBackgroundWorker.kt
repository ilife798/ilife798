package com.github.ilife798

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.github.ilife798.data.api.ApiConfig
import com.github.ilife798.data.api.DeviceWidgetSession
import com.github.ilife798.data.viewmodel.BackgroundAccountResult
import com.github.ilife798.data.viewmodel.TaskBackgroundRunner
import com.github.ilife798.util.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 定时任务「后台运行」的执行体。
 *
 * 到点后由 [TaskAlarmReceiver] 排进 WorkManager，由系统在后台调度执行，
 * **不拉起界面、不切号**：直接对每个已登录账号用它自己的 token 发请求，
 * 因此不会打断用户正在做的事。
 *
 * 用 expedited 请求 + [setForeground] 让系统把它当作前台服务级别的任务对待，
 * 否则长任务在后台容易被省电策略掐断。
 */
class TaskBackgroundWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ScheduledTasks.bind(applicationContext)
        val schedule = ScheduledTasks.currentSchedule()
        if (!schedule.enabled || !schedule.runInBackground) {
            // 计划已关闭或已切回前台运行：本次触发作废
            return Result.success()
        }
        if (ApiConfig.baseUrl.isBlank()) {
            ApiConfig.init(
                BuildConfig.API_GATEWAY,
                BuildConfig.SIGN_SALT,
                BuildConfig.API_CID,
                miniSalt = BuildConfig.MINI_SIGN_SALT,
            )
        }
        return try {
            setForeground(foregroundInfo(INITIAL_TEXT))
            runTasks()
            Result.success()
        } catch (e: Exception) {
            logDebug("ILife798", "TaskBackgroundWorker: ${e.stackTraceToString()}")
            notifyResult("定时任务执行失败", e.message ?: "未知错误")
            Result.failure()
        }
    }

    private suspend fun runTasks() {
        val accounts =
            withContext(Dispatchers.IO) {
                DeviceWidgetSession.accounts(applicationContext)
            }
        if (accounts.isEmpty()) {
            notifyResult("定时任务已跳过", "没有已登录的账号")
            return
        }
        val session = DeviceWidgetSession()
        try {
            val runner = TaskBackgroundRunner(api = session.api) { logDebug("ILife798", it) }
            runner.onAccountStart = { index, total, label ->
                updateProgress("正在执行 $label（$index/$total）")
            }
            val results = withContext(Dispatchers.IO) { runner.runAll(accounts) }
            notifyResult("定时任务已完成", summary(results))
        } finally {
            session.close()
        }
    }

    private fun summary(results: List<BackgroundAccountResult>): String =
        if (results.isEmpty()) {
            "没有需要执行的账号"
        } else {
            results.joinToString("\n") { "${it.label} · ${it.message}" }
        }

    /**
     * expedited 任务必须先声明为前台服务，否则系统会在 10 秒内把它当作后台任务掐掉。
     * 这里复用积分任务的运行通知样式，用户看到的是同一套视觉。
     */
    private fun foregroundInfo(text: String): ForegroundInfo {
        ScheduledTaskNotifications.ensureChannel(applicationContext)
        val notification = ScheduledTaskNotifications.runningNotification(applicationContext, text)
        return ForegroundInfo(SCHEDULED_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun updateProgress(text: String) {
        if (text == lastText) return
        lastText = text
        runCatching {
            val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                SCHEDULED_NOTIFICATION_ID,
                ScheduledTaskNotifications.runningNotification(applicationContext, text),
            )
        }
    }

    private fun notifyResult(
        title: String,
        text: String,
    ) {
        if (!ScheduledTaskNotifications.canNotify(applicationContext)) {
            return
        }
        runCatching {
            val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                RESULT_NOTIFICATION_ID,
                ScheduledTaskNotifications.resultNotification(applicationContext, title, text),
            )
        }
    }

    private var lastText: String = ""

    companion object {
        private const val SCHEDULED_NOTIFICATION_ID = 2002
        private const val RESULT_NOTIFICATION_ID = 2003
        private const val INITIAL_TEXT = "正在后台执行各账号的积分任务"
    }
}
