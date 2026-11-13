package com.github.ilife798

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.data.model.TaskProgress

/**
 * 后台执行（定时任务的「后台运行」）与界面之间的唯一通道。
 *
 * 后台路径不能直接读写 `AppState`：闹钟到点时进程可能刚被系统拉起，
 * `AppViewModel` 还在初始化甚至根本不存在。所以这里放一层进程级单例：
 * 后台侧只负责「报告状态」，界面侧由 [com.github.ilife798.data.viewmodel.AppViewModel]
 * 订阅后翻译成 `isLoading` / 运行日志 / 停止按钮，两边都不跨层引用。
 *
 * 同时它也是两条运行路径的互斥闸门：
 * - [uiRunning] 由界面路径（`TaskController`）置位，后台启动前检查；
 * - [backgroundRunning] 由后台路径置位，界面启动前检查。
 *
 * 同一账号被两条路径并发执行会打服务端频控（-98），两轮都会失败，必须互相让路。
 */
object TaskRunBridge {
    /** 后台（前台服务 / WorkManager）是否正在执行。界面据此点亮「运行中」并锁定选择。 */
    var backgroundRunning: Boolean by mutableStateOf(false)
        private set

    // 用私有 backing 属性而不是 `private set`：后者生成的 setter 会与 setUiRunning 撞 JVM 签名
    private var uiRunningState: Boolean by mutableStateOf(false)

    /** 界面（任务页按钮、快捷方式）是否正在执行。后台据此跳过本轮，避免并发。 */
    val uiRunning: Boolean
        get() = uiRunningState

    /** 后台当前的进度，界面可直接展示；不在后台运行时为 null。 */
    var progress: TaskProgress? by mutableStateOf(null)
        private set

    /** 后台当前账号前缀（单账号时为 null），与通知正文保持一致。 */
    var accountLabel: String? by mutableStateOf(null)
        private set

    /** 界面订阅后台日志：后台每产生一行就回调一次，由界面追加进运行日志列表。 */
    var onLog: ((String) -> Unit)? = null

    fun startBackground() {
        backgroundRunning = true
    }

    fun updateBackground(
        progress: TaskProgress,
        accountLabel: String?,
    ) {
        this.progress = progress
        this.accountLabel = accountLabel
    }

    fun appendBackgroundLog(message: String) {
        onLog?.invoke(message)
    }

    fun finishBackground() {
        backgroundRunning = false
        progress = null
        accountLabel = null
    }

    fun setUiRunning(running: Boolean) {
        uiRunningState = running
    }
}
