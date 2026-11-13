package com.github.ilife798

import android.content.Context
import com.github.ilife798.data.api.ApiConfig
import com.github.ilife798.data.api.DeviceWidgetSession
import com.github.ilife798.data.model.TaskProgress
import com.github.ilife798.data.viewmodel.BackgroundAccountResult
import com.github.ilife798.data.viewmodel.TaskBackgroundRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 定时任务「后台运行」的执行体，两个入口共用这一份实现，避免逻辑分叉：
 * - [ScheduledTaskService]：闹钟到点拉起的前台服务，进程优先级高，不容易被系统冻结；
 * - [TaskBackgroundWorker]：前台服务被系统拒绝时的降级通道，交给 WorkManager 调度。
 *
 * 两者都不碰界面：直接对每个已登录账号用它自己的 token 发请求，不会打断用户正在做的事。
 */
internal object ScheduledTaskRunner {
    /**
     * 执行结果。
     *
     * [skippedReason] 非空表示根本没有账号可跑（此时 [results] 为空）；
     * 否则 [results] 按账号顺序给出各自的执行摘要。
     */
    data class Outcome(
        val skippedReason: String? = null,
        val results: List<BackgroundAccountResult> = emptyList(),
    ) {
        fun summary(): String =
            when {
                skippedReason != null -> skippedReason
                results.isEmpty() -> "没有需要执行的账号"
                else -> results.joinToString("\n") { "${it.label} · ${it.message}" }
            }
    }

    /**
     * @param onProgress 进度回调，参数是进度快照与账号显示名（单账号时为 null）。
     *   用结构化快照而不是拼好的字符串，后台运行才能渲染出与手动运行完全一致的两行正文。
     */
    suspend fun run(
        context: Context,
        onProgress: (TaskProgress, String?) -> Unit,
    ): Outcome {
        // 界面上正在跑同一批账号时直接让路：两条路径并发执行会撞服务端频控（-98），两轮都失败
        if (TaskRunBridge.uiRunning) return Outcome(skippedReason = "应用内正在运行积分任务，已跳过本轮")
        ensureApiConfig()
        // 取整条 AccountEntry：昵称在 info 里，只取 Account 会退化成显示手机号
        val entries =
            withContext(Dispatchers.IO) {
                DeviceWidgetSession.accountEntries(context)
            }
        if (entries.isEmpty()) return Outcome(skippedReason = "没有已登录的账号")
        val session = DeviceWidgetSession()
        // 通知界面这一轮从后台开始：任务页据此点亮「运行中」、锁定账号勾选
        TaskRunBridge.startBackground()
        return try {
            val runner =
                TaskBackgroundRunner(
                    api = session.api,
                    // 用户点「停止」时置位，让循环在下一轮开始前就退出。
                    // 只停服务是不够的：stopService 要等 onDestroy 才取消协程，中间会继续发请求。
                    shouldStop = { TaskRunBridge.isStopRequested },
                    onLog = { message ->
                        logDebug("ILife798", message)
                        // 任务页开着时同步进运行日志，否则用户只能看到一片空白
                        TaskRunBridge.appendBackgroundLog(message)
                    },
                )
            runner.onProgress = { progress, label ->
                TaskRunBridge.updateBackground(progress, label)
                onProgress(progress, label)
            }
            Outcome(results = withContext(Dispatchers.IO) { runner.runAll(entries) })
        } finally {
            TaskRunBridge.finishBackground()
            session.close()
        }
    }

    /**
     * 后台进程里没有走应用启动流程，[ApiConfig] 还是空的，得自己补一次。
     * 已有配置时不要覆盖，否则会把前台阶段设置过的加速地址冲掉。
     */
    private fun ensureApiConfig() {
        if (ApiConfig.baseUrl.isNotBlank()) return
        ApiConfig.init(
            BuildConfig.API_GATEWAY,
            BuildConfig.SIGN_SALT,
            BuildConfig.API_CID,
            miniSalt = BuildConfig.MINI_SIGN_SALT,
        )
    }
}
