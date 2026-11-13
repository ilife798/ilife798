package com.github.ilife798

import android.app.NotificationManager
import android.app.PendingIntent
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
 * 定时任务「后台运行」的常驻前台服务。
 *
 * 为什么必须常驻而不是「到点才起来」：国产 ROM（ColorOS 的 `fastfreezer` 等）会把普通后台进程
 * 冻结成 `state: R -> F`，冻结期间闹钟广播投递不进来、WorkManager 也拿不到 CPU，
 * 表现就是「到点毫无反应」。挂着前台服务的进程优先级高得多，基本不会被冻。
 *
 * 三个入口：
 * - [TaskScheduler.ACTION_ENSURE_RESIDENT]：只把服务挂起来（保活），不执行任务；
 * - [TaskScheduler.ACTION_RUN_RESIDENT_TASKS]：闹钟到点，执行一轮；
 * - [TaskScheduler.ACTION_STOP_RESIDENT]：用户在保活通知上点「停止」。
 *
 * 保活期间前台通知是 [ScheduledTaskNotifications.RESIDENT_ID]（常驻那条）；
 * 执行期间进度另发一条 [ScheduledTaskNotifications.RUNNING_ID]，与手动运行完全同款，
 * 这样既保住了进程，也保住了「谁触发都长一样」的一致性。
 */
class ScheduledTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var foreground = false
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
        // 被 startForegroundService 拉起后必须在 5 秒内 startForeground，否则系统直接判 ANR 崩溃。
        // 所以无论后续走哪条分支，都先把保活通知挂上。
        promoteToForeground()
        val action = intent?.action
        if (action == TaskScheduler.ACTION_STOP_RESIDENT) {
            logDebug("ILife798", "ScheduledTaskService: 用户关闭后台保活")
            finish()
            return START_NOT_STICKY
        }
        val schedule = ScheduledTasks.currentSchedule()
        if (!schedule.enabled || !schedule.runInBackground) {
            logDebug("ILife798", "ScheduledTaskService: 计划已关闭或已改为前台运行，停止常驻")
            finish()
            return START_NOT_STICKY
        }
        if (action != TaskScheduler.ACTION_RUN_RESIDENT_TASKS) {
            // 只是要保活：服务留在这里，等闹钟来敲。START_STICKY 让系统在内存紧张回收后
            // 尽量把服务拉回来——没有它，被回收一次保活就断了，后续闹钟又得靠 WorkManager 兜底。
            // 顺手清掉上一轮被系统杀在半路时残留的「运行中」通知，否则它会一直挂在那。
            ScheduledTaskNotifications.cancelProgress(this)
            updateResidentNotification()
            logDebug("ILife798", "ScheduledTaskService: 已进入常驻")
            return START_STICKY
        }
        if (running) {
            // 同一时刻只跑一轮：上一轮还没结束就再来一次，只会互相抢 token 限流
            logDebug("ILife798", "ScheduledTaskService: 上一轮尚未结束，跳过本次触发")
            return START_STICKY
        }
        running = true
        acquireWakeLock()
        // 清掉上一轮可能残留的进度通知（例如进程上次被杀，通知永远停在「运行中」）
        ScheduledTaskNotifications.cancelProgress(this)
        scope.launch {
            try {
                executeTasks()
            } catch (e: CancellationException) {
                // 用户主动停止：这是正常收尾，不该再报一条「执行失败」。
                logDebug("ILife798", "ScheduledTaskService: 已被用户停止")
                notifyResult("定时任务已停止", "本轮执行被手动停止")
                throw e
            } catch (e: Exception) {
                logDebug("ILife798", "ScheduledTaskService: ${e.stackTraceToString()}")
                notifyResult("定时任务执行失败", e.message ?: "未知错误")
            } finally {
                finishRound()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        // 服务被停掉时，前台通知理论上会随 stopForeground 一起收走；但国产 ROM 上
        // 见过「服务没了、通知还挂着」的情况，这里再显式取消一次。
        ScheduledTaskNotifications.cancelResident(this)
        ScheduledTaskNotifications.cancelProgress(this)
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
        if (!running) return
        // 前台通知槽位被保活通知占着，进度走普通通知投递：两条通知并存，
        // 一条说明「后台运行开着」，一条显示本轮跑到哪了。
        ScheduledTaskNotifications.notifyProgress(this, progress, accountLabel)
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

    /** 一轮结束：撤掉进度通知，服务退回到「只保活」状态。 */
    private fun finishRound() {
        running = false
        ScheduledTaskNotifications.cancelProgress(this)
        releaseWakeLock()
        // 计划还在后台运行，就把前台通知换回保活那条；否则整个服务退出。
        val schedule = runCatching { ScheduledTasks.currentSchedule() }.getOrDefault(null)
        if (schedule?.enabled == true && schedule.runInBackground) {
            updateResidentNotification()
        } else {
            finish()
        }
    }

    private fun promoteToForeground() {
        if (foreground) return
        val ok =
            runCatching {
                startForeground(
                    ScheduledTaskNotifications.RESIDENT_ID,
                    ScheduledTaskNotifications.residentNotification(
                        this,
                        stopResidentIntent(),
                        residentNextRun(),
                    ),
                )
                true
            }.getOrDefault(false)
        foreground = ok
        if (!ok) logDebug("ILife798", "ScheduledTaskService: 保活通知挂起失败")
    }

    private fun updateResidentNotification() {
        if (!foreground) {
            promoteToForeground()
            return
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                ScheduledTaskNotifications.RESIDENT_ID,
                ScheduledTaskNotifications.residentNotification(
                    this,
                    stopResidentIntent(),
                    residentNextRun(),
                ),
            )
        }
    }

    private fun residentNextRun(): String? =
        runCatching {
            val schedule = ScheduledTasks.currentSchedule()
            if (schedule.enabled) TaskScheduler.describeNextRun(schedule) else null
        }.getOrDefault(null)

    private fun stopResidentIntent(): PendingIntent {
        val intent =
            Intent(this, TaskAlarmReceiver::class.java)
                .setAction(TaskScheduler.ACTION_STOP_RESIDENT)
        return PendingIntent.getBroadcast(
            this,
            REQUEST_STOP_RESIDENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun finish() {
        running = false
        foreground = false
        ScheduledTaskNotifications.cancelResident(this)
        ScheduledTaskNotifications.cancelProgress(this)
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

        private const val REQUEST_STOP_RESIDENT = 7304
    }
}
