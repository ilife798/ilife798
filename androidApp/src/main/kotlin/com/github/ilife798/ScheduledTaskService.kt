package com.github.ilife798

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.github.ilife798.data.model.TaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 定时任务「后台运行」的主力通道：闹钟到点后由 [TaskAlarmReceiver] 直接拉起。
 *
 * 之所以用前台服务而不是只靠 WorkManager：国产 ROM（ColorOS 的 `fastfreezer` 等）会把普通后台进程
 * 冻结成 `state: R -> F`，那样闹钟广播投递不进来、WorkManager 也拿不到 CPU；前台服务的进程优先级
 * 高得多，基本不会被冻。代价是必须常驻一个通知。
 *
 * 通知与手动运行共用同一条（[ScheduledTaskNotifications.RUNNING_ID]，即 `RUN_NOTIFICATION_ID`），
 * 正文也是同样的「账号名 | 积分:N」+「任务名 (X/X)」，用户不该因为「谁触发的」而看到两种样式。
 */
class ScheduledTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var lastProgress: TaskProgress? = null
    private var lastLabel: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ScheduledTasks.bind(applicationContext)
        ScheduledTaskNotifications.ensureChannel(this)
        // 被 startForegroundService 拉起后必须在 5 秒内 startForeground，否则系统直接判 ANR 崩溃，
        // 所以先把通知挂上，再判断这次触发还要不要继续。
        startForeground(
            ScheduledTaskNotifications.RUNNING_ID,
            ScheduledTaskNotifications.progressNotification(this, TaskProgress(), null),
        )
        val schedule = ScheduledTasks.currentSchedule()
        if (!schedule.enabled || !schedule.runInBackground) {
            logDebug("ILife798", "ScheduledTaskService: 计划已关闭或已改为前台运行，跳过")
            finish()
            return START_NOT_STICKY
        }
        if (running) {
            // 同一时刻只跑一轮：上一轮还没结束就再来一次，只会互相抢 token 限流
            logDebug("ILife798", "ScheduledTaskService: 上一轮尚未结束，跳过本次触发")
            return START_NOT_STICKY
        }
        running = true
        acquireWakeLock()
        scope.launch {
            try {
                executeTasks()
            } catch (e: CancellationException) {
                // 用户主动停止（通知上的「停止」会 stopService → onDestroy → scope.cancel）：
                // 这是正常收尾，不该再报一条「执行失败」。
                logDebug("ILife798", "ScheduledTaskService: 已被用户停止")
                notifyResult("定时任务已停止", "本轮执行被手动停止")
                throw e
            } catch (e: Exception) {
                logDebug("ILife798", "ScheduledTaskService: ${e.stackTraceToString()}")
                notifyResult("定时任务执行失败", e.message ?: "未知错误")
            } finally {
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun executeTasks() {
        val outcome = ScheduledTaskRunner.run(this, ::updateProgress)
        notifyResult(
            if (outcome.skippedReason != null) "定时任务已跳过" else "定时任务已完成",
            outcome.summary(),
        )
    }

    private fun updateProgress(
        progress: TaskProgress,
        accountLabel: String?,
    ) {
        if (progress == lastProgress && accountLabel == lastLabel) return
        lastProgress = progress
        lastLabel = accountLabel
        // 已经收尾时不再刷新，否则会把一个已停止的前台服务重新拉起来
        if (!running) return
        runCatching {
            startForeground(
                ScheduledTaskNotifications.RUNNING_ID,
                ScheduledTaskNotifications.progressNotification(this, progress, accountLabel),
            )
        }
    }

    private fun notifyResult(
        title: String,
        text: String,
    ) {
        if (!ScheduledTaskNotifications.canNotify(this)) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                ScheduledTaskNotifications.RESULT_ID,
                ScheduledTaskNotifications.resultNotification(this, title, text),
            )
        }
    }

    private fun finish() {
        running = false
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val manager = getSystemService(PowerManager::class.java)
            wakeLock =
                manager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        }.onFailure { logDebug("ILife798", "ScheduledTaskService.acquireWakeLock: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val WAKE_LOCK_TAG = "ILife798::ScheduledTask"

        // 上限 1 小时：一轮全账号任务再慢也不该超过这个量级，超时后交给系统回收更安全
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
