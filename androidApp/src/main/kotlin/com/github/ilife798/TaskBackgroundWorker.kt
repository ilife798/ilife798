package com.github.ilife798

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.github.ilife798.data.model.TaskProgress
import kotlinx.coroutines.CancellationException

/**
 * 定时任务「后台运行」的降级通道。
 *
 * 正常路径是闹钟广播 -> [TaskAlarmReceiver] 直接拉起 [ScheduledTaskService]（前台服务，进程不易被冻结）。
 * 只有系统拒绝后台启动前台服务时（Android 12+ 的 `ForegroundServiceStartNotAllowedException`，
 * 常见于没有精确闹钟权限的机型），才会排到这里，由 WorkManager 负责调度。
 *
 * 执行逻辑与前台服务共用 [ScheduledTaskRunner]，本类只负责 WorkManager 特有的部分：
 * 前台信息声明、通知刷新、结果通知。通知 id 与正文都与手动运行共用同一套。
 */
class TaskBackgroundWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var lastProgress: TaskProgress? = null
    private var lastLabel: String? = null

    override suspend fun doWork(): Result {
        logDebug("ILife798", "TaskBackgroundWorker: 开始执行 attempt=$runAttemptCount")
        ScheduledTasks.bind(applicationContext)
        val schedule = ScheduledTasks.currentSchedule()
        if (!schedule.enabled || !schedule.runInBackground) {
            // 计划已关闭或已切回前台运行：本次触发作废
            logDebug("ILife798", "TaskBackgroundWorker: 计划已失效，跳过")
            return Result.success()
        }
        return try {
            // expedited 给了约 10 秒的临时前台服务许可窗口，要尽早转前台；
            // 万一系统拒绝（配额、机型差异），也不能让整轮任务因此中止，故只记录不抛出。
            runCatching { setForeground(foregroundInfo(TaskProgress(), null)) }
                .onFailure { logDebug("ILife798", "TaskBackgroundWorker.setForeground: ${it.message}") }
            val outcome = ScheduledTaskRunner.run(applicationContext, ::updateProgress)
            notifyResult(
                if (outcome.skippedReason != null) "定时任务已跳过" else "定时任务已完成",
                outcome.summary(),
            )
            Result.success()
        } catch (e: CancellationException) {
            // 用户主动停止：正常收尾，不再报「执行失败」
            logDebug("ILife798", "TaskBackgroundWorker: 已被停止")
            notifyResult("定时任务已停止", "本轮执行被手动停止")
            throw e
        } catch (e: Exception) {
            logDebug("ILife798", "TaskBackgroundWorker: ${e.stackTraceToString()}")
            notifyResult("定时任务执行失败", e.message ?: "未知错误")
            Result.failure()
        }
    }

    /**
     * expedited 任务必须先声明为前台服务，否则系统会在 10 秒内把它当作后台任务掐掉。
     * 通知与手动运行同样是两行正文：`积分:N`（多账号时前面带账号名）/ `任务名 (X/X)`。
     */
    private fun foregroundInfo(
        progress: TaskProgress,
        accountLabel: String?,
    ): ForegroundInfo {
        ScheduledTaskNotifications.ensureChannel(applicationContext)
        val notification =
            ScheduledTaskNotifications.progressNotification(applicationContext, progress, accountLabel)
        return ForegroundInfo(SCHEDULED_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun updateProgress(
        progress: TaskProgress,
        accountLabel: String?,
    ) {
        if (progress == lastProgress && accountLabel == lastLabel) return
        lastProgress = progress
        lastLabel = accountLabel
        runCatching {
            val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.notify(
                SCHEDULED_NOTIFICATION_ID,
                ScheduledTaskNotifications.progressNotification(applicationContext, progress, accountLabel),
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
            val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.notify(
                RESULT_NOTIFICATION_ID,
                ScheduledTaskNotifications.resultNotification(applicationContext, title, text),
            )
        }
    }

    companion object {
        private const val SCHEDULED_NOTIFICATION_ID = ScheduledTaskNotifications.RUNNING_ID
        private const val RESULT_NOTIFICATION_ID = ScheduledTaskNotifications.RESULT_ID
    }
}
